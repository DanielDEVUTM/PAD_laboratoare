package com.pad.lab3.gateway;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** A registered Data Warehouse instance as seen by the gateway. */
public class Instance {
    public enum Status { UP, DOWN }

    public final String id;
    public volatile String url;
    public volatile Status status = Status.UP;
    public volatile int healthFailures = 0;
    public volatile Instant registeredAt = Instant.now();
    public volatile Instant lastHeartbeat = Instant.now();
    public volatile Instant lastHealthOk = null;
    public volatile Instant downSince = null;
    public final AtomicLong served = new AtomicLong();
    public final AtomicLong failed = new AtomicLong();
    public final AtomicBoolean checkInFlight = new AtomicBoolean(false);

    public Instance(String id, String url) {
        this.id = id;
        this.url = url;
    }

    public Map<String, Object> view() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("url", url);
        m.put("status", status.name());
        m.put("healthFailures", healthFailures);
        m.put("registeredAt", registeredAt.toString());
        m.put("lastHeartbeat", lastHeartbeat.toString());
        m.put("lastHealthOk", lastHealthOk == null ? null : lastHealthOk.toString());
        m.put("downSince", downSince == null ? null : downSince.toString());
        m.put("served", served.get());
        m.put("failed", failed.get());
        return m;
    }
}
