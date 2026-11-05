package com.pad.lab3.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Response cache kept in Redis (the small "memory" database next to the gateway).
 *
 * - Only successful GET responses are cached (TTL gateway.cache.ttl-seconds).
 * - Invalidation: every successful write bumps a global version number that is part of every key,
 *   so all previously cached reads become unreachable at once (and expire by TTL).
 * - Write guard: for a short window after a write nothing is stored, so a read served by a
 *   replica that has not yet caught up cannot poison the cache with stale data.
 * - If Redis is down the gateway keeps working without cache.
 */
@Service
public class CacheService {

    public record Cached(int status, String contentType, byte[] body) {}

    private static final String VERSION_KEY = "cache:version";

    private final JedisPool pool;
    private final long ttl;
    private final long writeGuardMs;
    private final EventLog events;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong stores = new AtomicLong();
    private final AtomicLong invalidations = new AtomicLong();
    private volatile long lastWriteAt = 0;
    private volatile long redisRetryAt = 0;
    private volatile boolean redisUp = false;

    public CacheService(@Value("${gateway.cache.redis-host}") String host,
                        @Value("${gateway.cache.redis-port}") int port,
                        @Value("${gateway.cache.ttl-seconds}") long ttl,
                        @Value("${gateway.cache.write-guard-ms}") long writeGuardMs,
                        EventLog events) {
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(64);
        cfg.setMaxIdle(16);
        cfg.setTestOnBorrow(false);
        this.pool = new JedisPool(cfg, host, port, 300);
        this.ttl = ttl;
        this.writeGuardMs = writeGuardMs;
        this.events = events;
    }

    /** Builds the cache key for a read, bound to the current data version. Null if Redis is unavailable. */
    public String key(String pathAndQuery, String accept) {
        if (System.currentTimeMillis() < redisRetryAt) return null;
        try (Jedis j = pool.getResource()) {
            String v = j.get(VERSION_KEY);
            markUp();
            return "cache:v" + (v == null ? "0" : v) + ":" + sha256((accept == null ? "" : accept) + "|" + pathAndQuery);
        } catch (Exception e) {
            markDown(e);
            return null;
        }
    }

    public Optional<Cached> get(String key) {
        if (key == null) return Optional.empty();
        try (Jedis j = pool.getResource()) {
            byte[] blob = j.get(key.getBytes(StandardCharsets.UTF_8));
            if (blob == null) {
                misses.incrementAndGet();
                return Optional.empty();
            }
            int nl1 = indexOf(blob, 0);
            hits.incrementAndGet();
            String ct = new String(blob, 0, nl1, StandardCharsets.UTF_8);
            return Optional.of(new Cached(200, ct.isEmpty() ? null : ct, Arrays.copyOfRange(blob, nl1 + 1, blob.length)));
        } catch (Exception e) {
            markDown(e);
            return Optional.empty();
        }
    }

    public void put(String key, String contentType, byte[] body) {
        if (key == null || System.currentTimeMillis() - lastWriteAt < writeGuardMs) return;
        byte[] ct = (contentType == null ? "" : contentType).getBytes(StandardCharsets.UTF_8);
        byte[] blob = new byte[ct.length + 1 + body.length];
        System.arraycopy(ct, 0, blob, 0, ct.length);
        blob[ct.length] = '\n';
        System.arraycopy(body, 0, blob, ct.length + 1, body.length);
        try (Jedis j = pool.getResource()) {
            j.setex(key.getBytes(StandardCharsets.UTF_8), ttl, blob);
            stores.incrementAndGet();
        } catch (Exception e) {
            markDown(e);
        }
    }

    /** Called after every successful write (POST/PUT/PATCH/DELETE). */
    public void invalidateAll() {
        lastWriteAt = System.currentTimeMillis();
        try (Jedis j = pool.getResource()) {
            j.incr(VERSION_KEY);
            invalidations.incrementAndGet();
        } catch (Exception e) {
            markDown(e);
        }
    }

    public Map<String, Object> stats() {
        long h = hits.get(), m = misses.get();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("redisUp", redisUp);
        s.put("hits", h);
        s.put("misses", m);
        s.put("hitRatio", h + m == 0 ? 0.0 : Math.round(1000.0 * h / (h + m)) / 1000.0);
        s.put("stores", stores.get());
        s.put("invalidations", invalidations.get());
        s.put("ttlSeconds", ttl);
        return s;
    }

    private void markUp() {
        if (!redisUp) events.add("CACHE", "Redis connected, response cache enabled");
        redisUp = true;
    }

    private void markDown(Exception e) {
        if (redisUp || redisRetryAt == 0) events.add("CACHE", "Redis unavailable, serving without cache (" + e.getClass().getSimpleName() + ")");
        redisUp = false;
        redisRetryAt = System.currentTimeMillis() + 5000;
    }

    private static int indexOf(byte[] b, int from) {
        for (int i = from; i < b.length; i++) if (b[i] == '\n') return i;
        return 0;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
