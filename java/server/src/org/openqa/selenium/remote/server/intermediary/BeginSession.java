package org.openqa.selenium.remote.server.intermediary;

import static org.openqa.selenium.remote.CapabilityType.BROWSER_NAME;

import com.google.common.collect.ImmutableSet;
import com.google.gson.stream.JsonReader;

import org.openqa.selenium.firefox.GeckoDriverService;
import org.openqa.selenium.remote.Dialect;
import org.openqa.selenium.remote.http.JsonHttpCommandCodec;
import org.openqa.selenium.remote.http.JsonHttpResponseCodec;
import org.openqa.selenium.remote.http.W3CHttpCommandCodec;
import org.openqa.selenium.remote.http.W3CHttpResponseCodec;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BeginSession {

  public ActiveSession doSomeMagic(
      InputStream in,
      Charset encoding) throws IOException {
    // It's entirely possible that the downstream end will have sent a payload that's larger
    // than we can stash in memory. Spool it to disk, but look for interesting bits.
    // We're going to support both the json and the w3c protocols here. We need to track which
    // dialects the downstream end speaks.
    Path temp = Files.createTempFile("new-session", ".json");

    Set<String> ossKeys = new HashSet<>();
    Set<String> alwaysMatchKeys = new HashSet<>();
    List<Map<String, Object>> firstMatchKeys = new LinkedList<>();
    String browserName = null;

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
          ossKeys.addAll(caps.keySet());
          if (ossKeys.contains(BROWSER_NAME)) {
            browserName = (String) caps.get(BROWSER_NAME);
          }
        } else if ("capabilities".equals(name)) {
          json.beginObject();

          while (json.hasNext()) {
            name = json.nextName();
            if ("alwaysMatch".equals(name)) {
              identifiedDialects.add(Dialect.W3C);

              Map<String, Object> caps = sparseCapabilities(json);
              alwaysMatchKeys.addAll(caps.keySet());
              if (alwaysMatchKeys.contains(BROWSER_NAME)) {
                browserName = (String) caps.get(BROWSER_NAME);
              }
            } else if ("firstMatch".equals(name)) {
              identifiedDialects.add(Dialect.W3C);

              json.beginArray();
              while (json.hasNext()) {
                Map<String, Object> caps = sparseCapabilities(json);
                firstMatchKeys.add(caps);
                if (caps.containsKey(BROWSER_NAME) && browserName == null) {
                  browserName = (String) caps.get(BROWSER_NAME);
                }
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

      ActiveSession actualSession;
      switch (browserName) {
        case "firefox":
          actualSession = new ServicedSession(
              GeckoDriverService.createDefaultService(),
              temp,
              ImmutableSet.of(Dialect.OSS));
          break;

        default:
          throw new IllegalArgumentException("Unknown browser requested: " + browserName);
      }

      System.out.println("identifiedDialects = " + identifiedDialects);
      System.out.println("ossKeys = " + ossKeys);
      System.out.println("alwaysMatchKeys = " + alwaysMatchKeys);
      System.out.println("firstMatchKeys = " + firstMatchKeys);

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

  private Map<String, Object> sparseCapabilities(JsonReader json) throws IOException {
    Map<String, Object> caps = new HashMap<>();

    json.beginObject();

    while (json.hasNext()) {
      String key = json.nextName();
      if (BROWSER_NAME.equals(key)) {
        caps.put(key, json.nextString());
      } else {
        caps.put(key, null);
        json.skipValue();
      }
    }

    json.endObject();

    return caps;
  }

}
