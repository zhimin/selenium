package org.openqa.selenium.remote.server.intermediary;


import com.google.common.base.Preconditions;

import org.openqa.selenium.remote.CommandCodec;
import org.openqa.selenium.remote.Dialect;
import org.openqa.selenium.remote.ProtocolHandshake;
import org.openqa.selenium.remote.Response;
import org.openqa.selenium.remote.ResponseCodec;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;
import org.openqa.selenium.remote.http.JsonHttpCommandCodec;
import org.openqa.selenium.remote.http.JsonHttpResponseCodec;
import org.openqa.selenium.remote.http.W3CHttpCommandCodec;
import org.openqa.selenium.remote.http.W3CHttpResponseCodec;
import org.openqa.selenium.remote.internal.ApacheHttpClient;
import org.openqa.selenium.remote.service.DriverService;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

class ServicedSession extends ActiveSession {

  private final DriverService service;
  private final HttpClient client;
  private final SessionCodec codec;
  private final Dialect dialect;
  private final Map<String, Object> capabilities;
  private final SessionId sessionId;

  public ServicedSession(
      DriverService service,
      Path rawCapabilities,
      Set<Dialect> detectedDialects) throws IOException {
    this.service = Preconditions.checkNotNull(service);

    service.start();

    client = new ApacheHttpClient.Factory().createClient(service.getUrl());

    long size = Files.size(rawCapabilities);
    try (InputStream unbuffered = Files.newInputStream(rawCapabilities);
         InputStream in = new BufferedInputStream(unbuffered)) {

      Optional<ProtocolHandshake.Result> result =
          new ProtocolHandshake().createSession(client, in, size);

      if (!result.isPresent()) {
        // TODO: create a response to send
        throw new IllegalStateException("Cannot find a matching browser");
      }

      // TODO: Don't assume the session is created successfully.
      ProtocolHandshake.Result actualResult = result.get();

      Response response = actualResult.createResponse();
      if ((response.getState() != null && !"success".equals(response.getState())) ||
          (response.getStatus() != null && 0 != response.getStatus())) {
        throw new IllegalStateException("Session did not get created properly");
      }

      capabilities = (Map<String, Object>) response.getValue();

      if (detectedDialects.contains(actualResult.getDialect())) {
        codec = new Passthrough(service.getUrl());
        dialect = actualResult.getDialect();
      } else {
        Dialect downstreamDialect = detectedDialects.iterator().next();
        Dialect upstreamDialect = actualResult.getDialect();

        CommandCodec<HttpRequest> downCommand = detectCommandCodec(downstreamDialect);
        ResponseCodec<HttpResponse> downResponse = detectResponseCodec(downstreamDialect);
        CommandCodec<HttpRequest> upCommand = detectCommandCodec(upstreamDialect);
        ResponseCodec<HttpResponse> upResponse = detectResponseCodec(upstreamDialect);

        codec = new ProtocolConverter(
            service.getUrl(),
            downCommand,
            downResponse,
            upCommand,
            upResponse);
        dialect = downstreamDialect;
      }

      sessionId = new SessionId(response.getSessionId());
    }
  }

  @Override
  public SessionCodec getCodec() {
    checkRunning();
    return codec;
  }

  @Override
  public Dialect getDialect() {
    return dialect;
  }

  @Override
  public Map<String, Object> getCapabilities() {
    checkRunning();
    return capabilities;
  }

  @Override
  public SessionId getId() {
    checkRunning();
    return sessionId;
  }

  @Override
  public void stop() {
    checkRunning();
    service.stop();
  }

  private CommandCodec<HttpRequest> detectCommandCodec(Dialect dialect) {
    switch (dialect) {
      case OSS:
        return new JsonHttpCommandCodec();

      case W3C:
        return new W3CHttpCommandCodec();

      default:
        throw new IllegalStateException("Unknown dialect: " + dialect);
    }
  }

  private ResponseCodec<HttpResponse> detectResponseCodec(Dialect dialect) {
    switch (dialect) {
      case OSS:
        return new JsonHttpResponseCodec();

      case W3C:
        return new W3CHttpResponseCodec();

      default:
        throw new IllegalStateException("Unknown dialect: " + dialect);
    }
  }

  private void checkRunning() {
    Preconditions.checkState(
        service != null && service.isRunning(),
        "Backing service is not running");
  }
}
