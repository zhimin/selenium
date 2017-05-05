package org.openqa.selenium.remote.server.intermediary;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import org.openqa.selenium.remote.SessionId;

import java.util.concurrent.TimeUnit;

class InflightSessions {

  private final Cache<SessionId, ActiveSession> allSessions;

  public InflightSessions() {
    allSessions = CacheBuilder.newBuilder()
        .expireAfterAccess(10, TimeUnit.MINUTES)
        .removalListener(notification -> {
          ActiveSession value = (ActiveSession) notification.getValue();
          value.stop();
        })
        .build();
  }

  public ActiveSession get(SessionId id) {
    return allSessions.getIfPresent(id);
  }

  public void register(ActiveSession session) {
    allSessions.put(session.getId(), session);
  }
}
