package com.chat.backend.realtime;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.UUID;

/** userId -> open sessions. A user can have several (multiple tabs/devices). */
@Component
public class ConnectionRegistry {

    private final ConcurrentHashMap<UUID, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    public void add(UUID userId, WebSocketSession s) {
        // compute() makes "create set + add" atomic. computeIfAbsent followed by add()
        // could lose the session if a concurrent remove() deleted the empty set in between.
        sessions.compute(userId, (k, set) -> {
            if (set == null) set = ConcurrentHashMap.newKeySet();
            set.add(s);
            return set;
        });
    }

    public void remove(UUID userId, WebSocketSession s) {
        sessions.compute(userId, (k, set) -> {
            set.remove(s);
            return set.isEmpty() ? null : set;
        });
    }

    public Collection<WebSocketSession> get(UUID userId) {
        return sessions.getOrDefault(userId, Set.of());
    }
}
