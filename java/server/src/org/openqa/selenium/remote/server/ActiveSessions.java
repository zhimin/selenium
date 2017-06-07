package org.openqa.selenium.remote.server;

import static java.util.concurrent.TimeUnit.MINUTES;
import static org.openqa.selenium.remote.BrowserType.CHROME;
import static org.openqa.selenium.remote.BrowserType.EDGE;
import static org.openqa.selenium.remote.BrowserType.FIREFOX;
import static org.openqa.selenium.remote.BrowserType.IE;
import static org.openqa.selenium.remote.BrowserType.SAFARI;
import static org.openqa.selenium.remote.CapabilityType.BROWSER_NAME;
import static org.openqa.selenium.remote.DesiredCapabilities.chrome;
import static org.openqa.selenium.remote.DesiredCapabilities.firefox;
import static org.openqa.selenium.remote.DesiredCapabilities.htmlUnit;
import static org.openqa.selenium.remote.Dialect.OSS;
import static org.openqa.selenium.remote.Dialect.W3C;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.openqa.selenium.Platform;
import org.openqa.selenium.SessionNotCreatedException;
import org.openqa.selenium.remote.Dialect;
import org.openqa.selenium.remote.SessionId;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

class ActiveSessions {

  private static final Logger LOG = Logger.getLogger(ActiveSessions.class.getName());

  private final Map<String, SessionFactory> factories;
  private Cache<SessionId, ActiveSession> allSessions;
  private DriverSessions legacyDriverSessions;

  public ActiveSessions(LogMethod logger) {
    this.legacyDriverSessions = new DefaultDriverSessions(
        Platform.getCurrent(),
        new DefaultDriverFactory(),
        new SystemClock());

    RemovalListener<SessionId, ActiveSession> listener = notification -> {
      logger.log("Removing session %s: %s", notification.getKey(), notification.getCause());
      ActiveSession session = notification.getValue();
      session.stop();
      legacyDriverSessions.deleteSession(notification.getKey());

      logger.log("Post removal: %s and %s", allSessions.asMap(),
                 legacyDriverSessions.getSessions());
    };

    this.allSessions = CacheBuilder.newBuilder()
        .expireAfterAccess(10, MINUTES)
        .removalListener(listener)
        .build();

    this.factories = ImmutableMap.of(
        chrome().getBrowserName(), new ServicedSession.Factory("org.openqa.selenium.chrome.ChromeDriverService"),
        firefox().getBrowserName(), new ServicedSession.Factory("org.openqa.selenium.firefox.GeckoDriverService"),
        htmlUnit().getBrowserName(), new InMemorySession.Factory(this));
  }

  public ActiveSession createSession(
      Path pathToCapabilitiesOnDisk,
      Map<String, Object> alwaysMatch,
      List<Map<String, Object>> firstMatch,
      Map<String, Object> ossKeys) {
    List<SessionFactory> browserGenerators = determineBrowser(
        ossKeys,
        alwaysMatch,
        firstMatch);

    ImmutableSet.Builder<Dialect> downstreamDialects = ImmutableSet.builder();
    // Favour OSS for now
    if (!ossKeys.isEmpty()) {
      downstreamDialects.add(OSS);
    }
    if (!alwaysMatch.isEmpty() || !firstMatch.isEmpty()) {
      downstreamDialects.add(W3C);
    }

    ActiveSession session = browserGenerators.stream()
        .map(func -> {
          try {
            return func.apply(pathToCapabilitiesOnDisk, downstreamDialects.build());
          } catch (Exception e) {
            LOG.log(Level.INFO, "Unable to start session.", e);
          }
          return null;
        })
        .filter(Objects::nonNull)
        .findFirst()
        .orElseThrow(() -> new SessionNotCreatedException("Unable to create a new session"));

    allSessions.put(session.getId(), session);
    return session;
  }

  private List<SessionFactory> determineBrowser(
      Map<String, Object> ossKeys,
      Map<String, Object> alwaysMatchKeys,
      List<Map<String, Object>> firstMatchKeys) {
    List<Map<String, Object>> allCapabilities = firstMatchKeys.stream()
        // remove null keys
        .map(caps -> ImmutableMap.<String, Object>builder().putAll(caps).putAll(alwaysMatchKeys).build())
        .collect(Collectors.toList());
    allCapabilities.add(ossKeys);

    // Can we figure out the browser from any of these?
    ImmutableList.Builder<SessionFactory> builder = ImmutableList.builder();
    for (Map<String, Object> caps : allCapabilities) {
      caps.entrySet().stream()
          .map(entry -> guessBrowserName(entry.getKey(), entry.getValue()))
          .filter(factories.keySet()::contains)
          .map(factories::get)
          .findFirst()
          .ifPresent(builder::add);
    }

    return builder.build();
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

  public void invalidate(SessionId id) {
    legacyDriverSessions.deleteSession(id);
    allSessions.invalidate(id);
  }

  public ActiveSession getIfPresent(SessionId id) {
    return allSessions.getIfPresent(id);
  }

  public void put(SessionId id, ActiveSession session) {
    allSessions.put(id, session);
  }

  public Set<SessionId> getKeys() {
    return ImmutableSet.copyOf(allSessions.asMap().keySet());
  }

  interface LogMethod {

    void log(String toLog);

    default void log(String message, Object arg0, Object... args) {
      Object[] toUse = new Object[1 + (args != null ? args.length : 0)];
      toUse[0] = arg0;
      if (args != null) {
        System.arraycopy(args, 0, toUse, 1, args.length);
      }
      log(String.format(message, toUse));
    }
  }
}
