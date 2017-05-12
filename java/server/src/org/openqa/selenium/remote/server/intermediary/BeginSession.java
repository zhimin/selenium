package org.openqa.selenium.remote.server.intermediary;

import static org.openqa.selenium.remote.BrowserType.CHROME;
import static org.openqa.selenium.remote.BrowserType.EDGE;
import static org.openqa.selenium.remote.BrowserType.FIREFOX;
import static org.openqa.selenium.remote.BrowserType.IE;
import static org.openqa.selenium.remote.BrowserType.SAFARI;
import static org.openqa.selenium.remote.CapabilityType.BROWSER_NAME;

import com.google.common.collect.ImmutableMap;
import com.google.gson.stream.JsonReader;

import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.edge.EdgeDriverService;
import org.openqa.selenium.firefox.GeckoDriverService;
import org.openqa.selenium.ie.InternetExplorerDriverService;
import org.openqa.selenium.remote.Dialect;
import org.openqa.selenium.safari.SafariDriverService;
import org.openqa.selenium.safari.SafariOptions;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class BeginSession {

  public ActiveSession doSomeMagic(
      InputStream in,
      Charset encoding) throws IOException {
    // It's entirely possible that the downstream end will have sent a payload that's larger
    // than we can stash in memory. Spool it to disk, but look for interesting bits.
    // We're going to support both the json and the w3c protocols here. We need to track which
    // dialects the downstream end speaks.
    Path temp = Files.createTempFile("new-session", ".json");

    Map<String, Object> ossKeys = new HashMap<>();
    Map<String, Object> alwaysMatchKeys = new HashMap<>();
    List<Map<String, Object>> firstMatchKeys = new LinkedList<>();

    Set<Dialect> identifiedDialects = new HashSet<>();
    try (
        OutputStream duplicate = new BufferedOutputStream(Files.newOutputStream(temp));
        TeeInputStream tee = new TeeInputStream(in, duplicate);
        JsonReader json = new JsonReader(new InputStreamReader(tee, encoding))) {

      json.beginObject();
      while (json.hasNext()) {
        String name = json.nextName();
        if ("desiredCapabilities".equals(name)) {
          identifiedDialects.add(Dialect.OSS);

          Map<String, Object> caps = sparseCapabilities(json);
          ossKeys.putAll(caps);
        } else if ("capabilities".equals(name)) {
          json.beginObject();

          while (json.hasNext()) {
            name = json.nextName();
            if ("alwaysMatch".equals(name)) {
              identifiedDialects.add(Dialect.W3C);

              Map<String, Object> caps = sparseCapabilities(json);
              alwaysMatchKeys.putAll(caps);
            } else if ("firstMatch".equals(name)) {
              identifiedDialects.add(Dialect.W3C);

              json.beginArray();
              while (json.hasNext()) {
                Map<String, Object> caps = sparseCapabilities(json);
                firstMatchKeys.add(caps);
              }
              json.endArray();
            } else {
              json.skipValue();
            }
          }
        } else {
          json.skipValue();
        }
      }
      json.endObject();

      tee.close();

      String selectedBrowser = determineBrowser(
          ossKeys,
          alwaysMatchKeys,
          firstMatchKeys);

      ActiveSession actualSession;
      switch (selectedBrowser) {
        case "chrome":
          actualSession = new ServicedSession(
              ChromeDriverService.createDefaultService(),
              temp,
              identifiedDialects);
          break;

        case "edge":
          actualSession = new ServicedSession(
              EdgeDriverService.createDefaultService(),
              temp,
              identifiedDialects);
          break;

        case "firefox":
          actualSession = new ServicedSession(
              GeckoDriverService.createDefaultService(),
              temp,
              identifiedDialects);
          break;

        case "internet explorer":
          actualSession = new ServicedSession(
              InternetExplorerDriverService.createDefaultService(),
              temp,
              identifiedDialects);
          break;

        case "safari":
          actualSession = new ServicedSession(
              SafariDriverService.createDefaultService(new SafariOptions()),
              temp,
              identifiedDialects);
          break;

        default:
          throw new IllegalArgumentException("Unknown browser requested: " + selectedBrowser);
      }

      return actualSession;
    } finally {
      Files.walk(temp)
          .sorted(Comparator.reverseOrder())
          .filter(Files::exists)
          .forEach(file -> {
            try {
              Files.delete(file);
            } catch (IOException e) {

            }
          });
    }
  }

  private String determineBrowser(
      Map<String, Object> ossKeys,
      Map<String, Object> alwaysMatchKeys,
      List<Map<String, Object>> firstMatchKeys) {
    List<Map<String, Object>> allCapabilities = firstMatchKeys.stream()
        // remove null keys
        .map(caps -> ImmutableMap.<String, Object>builder().putAll(caps).putAll(alwaysMatchKeys).build())
        .collect(Collectors.toList());
    allCapabilities.add(ossKeys);

    // Can we figure out the browser from any of these?
    for (Map<String, Object> caps : allCapabilities) {
      Optional<String> detected = caps.entrySet().stream()
          .map(entry -> guessBrowserName(entry.getKey(), entry.getValue()))
          .findFirst();
      if (detected.isPresent()) {
        return detected.get();
      }
    }

    return CHROME;
  }

  private String guessBrowserName(String capabilityKey, Object value) {
    if (BROWSER_NAME.equals(capabilityKey)) {
      return (String) value;
    }
    if ("chromeOptions".equals(capabilityKey)) {
      return CHROME;
    }
    if ("edgeOptions".equals(capabilityKey)) {
      return EDGE;
    }
    if (capabilityKey.startsWith("moz:")) {
      return FIREFOX;
    }
    if (capabilityKey.startsWith("safari.")) {
      return SAFARI;
    }
    if ("se:ieOptions".equals(capabilityKey)) {
      return IE;
    }
    return null;
  }

  private Map<String, Object> sparseCapabilities(JsonReader json) throws IOException {
    Map<String, Object> caps = new HashMap<>();

    json.beginObject();

    while (json.hasNext()) {
      String key = json.nextName();
      if (BROWSER_NAME.equals(key)) {
        caps.put(key, json.nextString());
      } else {
        caps.put(key, "");  // Must not be the null value
        json.skipValue();
      }
    }

    json.endObject();

    return caps;
  }

}
