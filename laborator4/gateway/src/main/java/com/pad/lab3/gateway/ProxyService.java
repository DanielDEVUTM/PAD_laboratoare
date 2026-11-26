package com.pad.lab3.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Request pipeline:
 *   1. GET? -> look in the Redis cache (HIT returns immediately).
 *   2. Otherwise the request is queued by priority and handled by an upstream worker.
 *   3. The worker picks an instance (round-robin). The gateway REMEMBERS the request, so if the
 *      instance does not answer in time (timeout / connection error) the circuit to it is opened
 *      and the very same request is re-sent to another instance, transparently for the client.
 *   4. A successful write invalidates the cache.
 */
@Service
public class ProxyService {

    public record ProxyRequest(String method, String pathAndQuery, Map<String, String> headers, byte[] body,
                               Priority priority, boolean noCache) {}

    public record ProxyResponse(int status, Map<String, String> headers, byte[] body) {}

    private final InstanceRegistry registry;
    private final CacheService cache;
    private final PriorityExecutor executor;
    private final EventLog events;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(1)).build();
    private final Duration timeout;
    private final int maxAttempts;

    public ProxyService(InstanceRegistry registry, CacheService cache, PriorityExecutor executor, EventLog events,
                        @Value("${gateway.proxy.request-timeout-ms}") long timeoutMs,
                        @Value("${gateway.proxy.max-attempts}") int maxAttempts) {
        this.registry = registry;
        this.cache = cache;
        this.executor = executor;
        this.events = events;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.maxAttempts = maxAttempts;
    }

    public ProxyResponse forward(ProxyRequest req) {
        boolean isGet = req.method().equals("GET");
        String cacheKey = null;
        if (isGet && !req.noCache()) {
            cacheKey = cache.key(req.pathAndQuery(), req.headers().get("Accept"));
            Optional<CacheService.Cached> hit = cache.get(cacheKey);
            if (hit.isPresent()) {
                Map<String, String> h = new LinkedHashMap<>();
                if (hit.get().contentType() != null) h.put("Content-Type", hit.get().contentType());
                h.put("X-Cache", "HIT");
                h.put("X-Served-By", "cache");
                return new ProxyResponse(200, h, hit.get().body());
            }
        }

        ProxyResponse r;
        try {
            r = executor.submit(req.priority(), () -> forwardWithRetry(req)).get(30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return error(504, "gateway_timeout", "Request waited too long in the queue");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error(500, "interrupted", "interrupted");
        } catch (ExecutionException e) {
            return error(500, "internal_error", String.valueOf(e.getCause()));
        }

        Map<String, String> h = new LinkedHashMap<>(r.headers());
        h.put("X-Priority-Applied", req.priority().name());
        if (isGet) {
            h.put("X-Cache", req.noCache() ? "BYPASS" : "MISS");
            if (!req.noCache() && r.status() == 200) {
                cache.put(cacheKey, r.headers().get("Content-Type"), r.body());
            }
        } else if (r.status() / 100 == 2) {
            cache.invalidateAll();
        }
        return new ProxyResponse(r.status(), h, r.body());
    }

    private ProxyResponse forwardWithRetry(ProxyRequest req) {
        String requestId = UUID.randomUUID().toString();   // lets the node deduplicate a retried POST
        Set<String> tried = new LinkedHashSet<>();
        String lastError = null;
        int attempts = 0;
        while (attempts < maxAttempts) {
            Instance inst = registry.pick(tried);
            if (inst == null) break;
            attempts++;
            tried.add(inst.id);
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(inst.url + req.pathAndQuery())).timeout(timeout);
                req.headers().forEach(b::header);
                b.header("X-Request-Id", requestId);
                b.method(req.method(), req.body().length == 0
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(req.body()));
                HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
                int st = r.statusCode();
                if (st == 502 || st == 503 || st == 504) {
                    lastError = inst.id + ": HTTP " + st;
                    registry.recordSoftFailure(inst, "HTTP " + st);
                    events.add("RETRY", req.method() + " " + req.pathAndQuery() + " failed on " + inst.id + " (HTTP " + st + "), trying another instance");
                    continue;
                }
                registry.recordSuccess(inst);
                Map<String, String> h = new LinkedHashMap<>();
                r.headers().firstValue("Content-Type").ifPresent(v -> h.put("Content-Type", v));
                r.headers().firstValue("Location").ifPresent(v -> h.put("Location", v));
                h.put("X-Served-By", inst.id);
                h.put("X-Attempts", String.valueOf(attempts));
                h.put("X-Request-Id", requestId);
                return new ProxyResponse(st, h, r.body());
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                String why = e instanceof java.net.http.HttpTimeoutException ? "timeout after " + timeout.toMillis() + " ms"
                        : e.getClass().getSimpleName();
                lastError = inst.id + ": " + why;
                registry.recordBreakingFailure(inst, why);
                events.add("RETRY", req.method() + " " + req.pathAndQuery() + " got no answer from " + inst.id + " (" + why + "), re-sending to another instance");
            }
        }
        if (attempts == 0) return error(503, "no_available_instances", "No healthy Data Warehouse instance is available");
        return error(502, "all_attempts_failed", "Tried " + tried + " - last error: " + lastError);
    }

    static ProxyResponse error(int status, String code, String message) {
        String json = "{\"status\":" + status + ",\"error\":\"" + code + "\",\"message\":\"" + message.replace("\"", "'") + "\"}";
        return new ProxyResponse(status, Map.of("Content-Type", "application/json"), json.getBytes());
    }
}
