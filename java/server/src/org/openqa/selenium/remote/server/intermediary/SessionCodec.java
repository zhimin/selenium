package org.openqa.selenium.remote.server.intermediary;

import org.openqa.selenium.remote.http.HttpResponse;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

interface SessionCodec {
  void handle(HttpServletRequest req, HttpServletResponse resp) throws IOException;
}
