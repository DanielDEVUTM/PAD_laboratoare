package com.pad.lab3.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Health check: every second (configurable) GET /health on every registered instance.
 * Checks are asynchronous so one hung instance never delays the checks of the others.
 */
@Component
public class HealthChecker {

    private final InstanceRegistry registry;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(1)).build();
    private final Duration timeout;
    private final int failuresToDown;
    private final long evictAfterMs;

    public HealthChecker(InstanceRegistry registry,
                         @Value("${gateway.health.timeout-ms}") long timeoutMs,
                         @Value("${gateway.health.failures-to-down}") int failuresToDown,
                         @Value("${gateway.registry.evict-after-ms}") long evictAfterMs) {
        this.registry = registry;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.failuresToDown = failuresToDown;
        this.evictAfterMs = evictAfterMs;
    }

    @Scheduled(fixedRateString = "${gateway.health.interval-ms}")
    public void checkAll() {
        for (Instance i : registry.all()) {
            if (i.status == Instance.Status.DOWN && i.downSince != null
                    && Instant.now().isAfter(i.downSince.plusMillis(evictAfterMs))
                    && Instant.now().isAfter(i.lastHeartbeat.plusMillis(evictAfterMs))) {
                registry.evict(i);
                continue;
            }
            if (!i.checkInFlight.compareAndSet(false, true)) continue;
            HttpRequest req = HttpRequest.newBuilder(URI.create(i.url + "/health")).timeout(timeout).GET().build();
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).whenComplete((resp, err) -> {
                try {
                    if (err == null && resp.statusCode() == 200) {
                        registry.markUp(i);
                    } else {
                        i.healthFailures++;
                        if (i.healthFailures >= failuresToDown) {
                            registry.markDown(i, err != null ? rootMessage(err) : "HTTP " + resp.statusCode());
                        }
                    }
                } finally {
                    i.checkInFlight.set(false);
                }
            });
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null) r = r.getCause();
        return r.getClass().getSimpleName();
    }
}
