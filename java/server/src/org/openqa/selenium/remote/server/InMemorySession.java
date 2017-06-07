// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.openqa.selenium.remote.server;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.openqa.selenium.remote.Dialect.OSS;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.io.CharStreams;

import org.openqa.selenium.Capabilities;
import org.openqa.selenium.ImmutableCapabilities;
import org.openqa.selenium.SessionNotCreatedException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.io.TemporaryFilesystem;
import org.openqa.selenium.remote.CommandCodec;
import org.openqa.selenium.remote.CommandExecutor;
import org.openqa.selenium.remote.Dialect;
import org.openqa.selenium.remote.JsonToBeanConverter;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.ResponseCodec;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;
import org.openqa.selenium.remote.http.JsonHttpCommandCodec;
import org.openqa.selenium.remote.http.JsonHttpResponseCodec;
import org.openqa.selenium.remote.http.W3CHttpCommandCodec;
import org.openqa.selenium.remote.http.W3CHttpResponseCodec;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.logging.Logger;
import java.util.stream.Collectors;


/**
 * Wraps an existing {@link org.openqa.selenium.WebDriver} instance and provides it with the OSS
 * wire protocol remote end points.
 */
class InMemorySession implements ActiveSession {

  private final SessionId id;
  private final Session session;
  private JsonHttpCommandHandler commandHandler;
  private Dialect downstreamDialect;

  public InMemorySession(
      SessionId id,
      Session session,
      JsonHttpCommandHandler commandHandler,
      Dialect downstreamDialect) {
    this.id = id;
    this.session = session;
    this.commandHandler = commandHandler;
    this.downstreamDialect = downstreamDialect;
  }

  @Override
  public SessionId getId() {
    return id;
  }

  @Override
  public Dialect getUpstreamDialect() {
    return OSS;
  }

  @Override
  public Dialect getDownstreamDialect() {
    return downstreamDialect;
  }

  @Override
  public Map<String, Object> getCapabilities() {
    return session.getCapabilities().asMap().entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  @Override
  public void stop() {
    session.close();
  }

  @Override
  public void execute(HttpRequest req, HttpResponse resp) throws IOException {
    commandHandler.handleRequest(req, resp);
  }

  public static class Factory implements SessionFactory {

    private final ActiveSessions activeSessions;

    public Factory(ActiveSessions activeSessions) {
      this.activeSessions = activeSessions;
    }

    @Override
    public ActiveSession apply(Path path, Set<Dialect> downstreamDialects) {
      try (BufferedReader reader = Files.newBufferedReader(path, UTF_8)) {
        Map<?, ?> blob = new JsonToBeanConverter().convert(Map.class, CharStreams.toString(reader));

        Map<String, ?> rawCaps = (Map<String, ?>) blob.get("desiredCapabilities");
        if (rawCaps == null) {
          rawCaps = new HashMap<>();
        }

        ActiveSession activeSession = activeSessions.createSession(
            path,
            ImmutableMap.of(),
            ImmutableList.of(),
            (Map<String, Object>) rawCaps);

        Session session = new SpoofedSession(activeSessions, activeSession);

        // Force OSS dialect if downstream speaks it
        Dialect downstream = downstreamDialects.contains(OSS) ?
                             OSS :
                             Iterables.getFirst(downstreamDialects, null);

        DriverSessions legacySessions = new SpoofedSessions(activeSessions);
        JsonHttpCommandHandler jsonHttpCommandHandler = new JsonHttpCommandHandler(
            legacySessions,
            Logger.getLogger(InMemorySession.class.getName()));

        return new InMemorySession(
            activeSession.getId(),
            session,
            jsonHttpCommandHandler,
            downstream);
      } catch (Exception e) {
        throw new SessionNotCreatedException("Unable to create session", e);
      }
    }
  }

  private static class SpoofedSessions implements DriverSessions {

    private ActiveSessions activeSessions;

    public SpoofedSessions(ActiveSessions activeSessions) {
      this.activeSessions = activeSessions;
    }

    @Override
    public SessionId newSession(Capabilities desiredCapabilities) throws Exception {
      return null;
    }

    @Override
    public Session get(SessionId sessionId) {
      return null;
    }

    @Override
    public void deleteSession(SessionId sessionId) {
      activeSessions.invalidate(sessionId);
    }

    @Override
    public void registerDriver(Capabilities capabilities,
                               Class<? extends WebDriver> implementation) {
      throw new UnsupportedOperationException("registerDriver");
    }

    @Override
    public Set<SessionId> getSessions() {
      return activeSessions.getKeys();
    }
  }

  private static class SpoofedSession implements Session {

    private final ActiveSessions activeSessions;
    private final ActiveSession session;
    private final TemporaryFilesystem tempFs;
    private final WebDriver driver;
    private KnownElements knownElements;

    public SpoofedSession(ActiveSessions activeSessions, ActiveSession session) {
      this.activeSessions = activeSessions;
      this.session = session;

      Path root = null;
      try {
        root = Files.createTempDirectory("old-skool-session");
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      this.tempFs = TemporaryFilesystem.getTmpFsBasedOn(root.toAbsolutePath().toFile());

      CommandCodec<HttpRequest> commandCodec;
      switch (session.getUpstreamDialect()) {
        case OSS:
          commandCodec = new JsonHttpCommandCodec();
          break;

        case W3C:
          commandCodec = new W3CHttpCommandCodec();
          break;

        default:
          throw new IllegalArgumentException(
              "Unrecognised upstream codec: " + session.getUpstreamDialect());
      }

      ResponseCodec<HttpResponse> responseCodec;
      switch (session.getDownstreamDialect()) {
        case OSS:
          responseCodec = new JsonHttpResponseCodec();
          break;

        case W3C:
          responseCodec = new W3CHttpResponseCodec();
          break;

        default:
          throw new IllegalArgumentException(
              "Unrecognised upstream codec: " + session.getDownstreamDialect());
      }

      CommandExecutor executor = cmd -> {
        HttpRequest req = commandCodec.encode(cmd);
        HttpResponse res = new HttpResponse();
        session.execute(req, res);
        return responseCodec.decode(res);
      };

      this.driver = new RemoteWebDriver(executor, getCapabilities());
      this.knownElements = new KnownElements();
    }

    @Override
    public void close() {
      activeSessions.invalidate(session.getId());
      tempFs.deleteBaseDir();
    }

    @Override
    public <X> X execute(FutureTask<X> future) throws Exception {
      throw new UnsupportedOperationException("execute");
    }

    @Override
    public WebDriver getDriver() {
      return driver;
    }

    @Override
    public KnownElements getKnownElements() {
      return knownElements;
    }

    @Override
    public Capabilities getCapabilities() {
      return new ImmutableCapabilities(session.getCapabilities());
    }

    @Override
    public void attachScreenshot(String base64EncodedImage) {
      // Do nothing
    }

    @Override
    public String getAndClearScreenshot() {
      // This is legit
      return null;
    }

    @Override
    public boolean isTimedOut(long timeout) {
      return false;
    }

    @Override
    public boolean isInUse() {
      return false;
    }

    @Override
    public void updateLastAccessTime() {
      // no-op
    }

    @Override
    public SessionId getSessionId() {
      return session.getId();
    }

    @Override
    public TemporaryFilesystem getTemporaryFileSystem() {
      return tempFs;
    }
  }
}
