package org.openqa.selenium.remote.server.intermediary;

import org.openqa.selenium.remote.Dialect;
import org.openqa.selenium.remote.SessionId;

import java.io.IOException;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

abstract class ActiveSession {

  public void handle(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    getCodec().handle(req, resp);
    if ("delete".equalsIgnoreCase(req.getMethod()) &&
        ("/session/" + getId()).equals(req.getPathInfo())) {
      stop();
    }
  }

  public abstract void stop();

  protected abstract SessionCodec getCodec();

  protected abstract SessionId getId();

  public abstract Dialect getDialect();

  protected abstract Map<String, Object> getCapabilities();
}
