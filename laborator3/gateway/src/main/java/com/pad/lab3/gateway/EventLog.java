package com.pad.lab3.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/** Small in-memory ring buffer of gateway events (shown in the UI). */
@Component
public class EventLog {
    private static final Logger log = LoggerFactory.getLogger(EventLog.class);
    private static final int MAX = 200;
    private final ConcurrentLinkedDeque<Map<String, Object>> events = new ConcurrentLinkedDeque<>();

    public void add(String type, String message) {
        log.info("[{}] {}", type, message);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("time", Instant.now().toString());
        e.put("type", type);
        e.put("message", message);
        events.addFirst(e);
        while (events.size() > MAX) events.pollLast();
    }

    public List<Map<String, Object>> recent() {
        return new ArrayList<>(events);
    }
}
