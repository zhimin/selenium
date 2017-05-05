package org.openqa.selenium.remote.server.intermediary;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import org.openqa.selenium.remote.ErrorCodes;
import org.openqa.selenium.remote.Response;
import org.openqa.selenium.remote.SessionId;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class WebDriverServlet extends HttpServlet {

  private static final Pattern UUID_PATTERN = Pattern.compile("([\\p{XDigit}-]+)");

  private final Gson gson = new Gson();
  private InflightSessions sessions;

  @Override
  public void init() throws ServletException {
    sessions = getInflightSessions();
  }

  @Override
  protected void service(HttpServletRequest req, HttpServletResponse resp)
      throws ServletException, IOException {
    String path = req.getPathInfo();
    if (Strings.isNullOrEmpty(path)) {
      path = "/";
    }

    if (isSessionFree(path, req, resp)) {
      return;
    }

    // Attempt to find the session id.
    ActiveSession session = null;
    Matcher matcher = UUID_PATTERN.matcher(path);
    while (session == null && matcher.find()) {
      SessionId id = new SessionId(matcher.group(1));
      session = sessions.get(id);
    }

    // If session is null, don't panic. It might be one of a tiny subset of URLs we respond to that
    // don't need this.
    if (session == null) {
      throw new ServletException("No session found");
    }

    session.handle(req, resp);
  }

  private boolean isSessionFree(String path, HttpServletRequest req, HttpServletResponse resp) {
    if (path.equals("/session") && "POST".equalsIgnoreCase(req.getMethod())) {
      BeginSession beginSession = new BeginSession();
      try (InputStream in = req.getInputStream()) {
        ActiveSession session =
            beginSession.doSomeMagic(in, Charset.forName(req.getCharacterEncoding()));
        sessions.register(session);

        Response response = new Response(session.getId());
        response.setStatus(ErrorCodes.SUCCESS);
        response.setState("success");
        response.setValue(session.getCapabilities());

        Map<String, Object> payload;

        switch (session.getDialect()) {
          case OSS:
            payload = ImmutableMap.of(
                "status", 0,
                "sessionId", session.getId().toString(),
                "value", session.getCapabilities());
            break;

          case W3C:
            payload = ImmutableMap.of(
                "state", "success",
                "value", ImmutableMap.of(
                    "sessionId", session.getId().toString(),
                    "capabilities", session.getCapabilities()));

            break;

          default:
            throw new ServletException("Unexpected dialect: " + session.getDialect());
        }

        log(gson.toJson(payload));

        byte[] bytes = gson.toJson(payload).getBytes(UTF_8);

        resp.setContentLengthLong(bytes.length);
        resp.setHeader("Content-Type", "application/json; charset=utf-8");
        resp.setHeader("Cache-Control", "no-cache");

        try (OutputStream out = resp.getOutputStream()) {
          ByteStreams.copy(new ByteArrayInputStream(bytes), out);
        }
      } catch (Exception e) {
        throw new RuntimeException(e);
      }

      return true;
    }

    return false;
  }

  private InflightSessions getInflightSessions() {
    InflightSessions sessions =
        (InflightSessions) getServletContext().getAttribute("webdriver.sessions");

    if (sessions == null) {
      synchronized (WebDriverServlet.class) {
        if (sessions == null) {
          sessions = new InflightSessions();
          getServletContext().setAttribute("webdriver.sessions", sessions);
        }
      }
    }
    return sessions;
  }

  public void spool(JsonReader in, JsonWriter out) throws IOException {
  }
}
