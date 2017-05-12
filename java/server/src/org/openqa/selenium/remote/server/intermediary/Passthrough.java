package org.openqa.selenium.remote.server.intermediary;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Objects;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class Passthrough implements SessionCodec {

  private final static Logger LOG = Logger.getLogger(Passthrough.class.getName());

  private final static ImmutableSet<String> IGNORED_REQ_HEADERS = ImmutableSet.<String>builder()
      .add("connection")
      .add("keep-alive")
      .add("proxy-authorization")
      .add("proxy-authenticate")
      .add("proxy-connection")
      .add("te")
      .add("trailer")
      .add("transfer-encoding")
      .add("upgrade")
      .build();

  private final URL upstream;

  public Passthrough(URL upstream) {
    this.upstream = upstream;
  }

  @Override
  public void handle(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    String suffix = req.getPathInfo();
    if (Strings.isNullOrEmpty(suffix)) {
      suffix = "/";
    }

    URL target = new URL(upstream.toExternalForm() + suffix);
    HttpURLConnection connection = (HttpURLConnection) target.openConnection();
    connection.setInstanceFollowRedirects(true);
    connection.setRequestMethod(req.getMethod());
    connection.setDoInput(true);
    connection.setDoOutput(true);
    connection.setUseCaches(false);

    Enumeration<String> allHeaders = req.getHeaderNames();
    while (allHeaders.hasMoreElements()) {
      String name = allHeaders.nextElement();
      if (IGNORED_REQ_HEADERS.contains(name.toLowerCase())) {
        continue;
      }

      Enumeration<String> allValues = req.getHeaders(name);
      while (allValues.hasMoreElements()) {
        String value = allValues.nextElement();
        connection.addRequestProperty(name, value);
      }
    }

    if ("POST".equalsIgnoreCase(req.getMethod()) || "PUT".equalsIgnoreCase(req.getMethod())) {
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      try (InputStream in = new TeeInputStream(req.getInputStream(), bos);
           OutputStream out = connection.getOutputStream()) {
        ByteStreams.copy(in, out);
      }
      String
          charSet =
          req.getCharacterEncoding() != null ? req.getCharacterEncoding() : UTF_8.name();
      LOG.info("To upstream: " + bos.toString(charSet));
    }

    resp.setStatus(connection.getResponseCode());
    // clear response defaults.
    resp.setHeader("Date",null);
    resp.setHeader("Server",null);

    connection.getHeaderFields().entrySet().stream()
        .filter(entry -> entry.getKey() != null && entry.getValue() != null)
        .filter(entry -> !IGNORED_REQ_HEADERS.contains(entry.getKey().toLowerCase()))
        .forEach(entry -> {
          entry.getValue().stream()
              .filter(Objects::nonNull)
              .forEach(value -> {
                resp.addHeader(entry.getKey(), value);
              });
        });
    InputStream in = connection.getErrorStream();
    if (in == null) {
      in = connection.getInputStream();
    }

    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (
        InputStream tee = new TeeInputStream(in, bos);
        OutputStream out = resp.getOutputStream()) {
      ByteStreams.copy(tee, out);
    } finally {
      in.close();
    }
    String charSet = connection.getContentEncoding() != null ? connection.getContentEncoding() : UTF_8.name();
    LOG.info("To downstream: " + bos.toString(charSet));
  }
}
