package com.pad.lab3.gateway;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service discovery: the registry of Data Warehouse instances.
 * Instances register themselves; the HealthChecker flips them UP/DOWN; pick() does round-robin.
 */
@Component
public class InstanceRegistry {

    private final ConcurrentHashMap<String, Instance> instances = new ConcurrentHashMap<>();
    private final AtomicInteger rr = new AtomicInteger();
    private final EventLog events;

    private final long breakerCooldownMs;

    public InstanceRegistry(EventLog events,
                            @org.springframework.beans.factory.annotation.Value("${gateway.proxy.breaker-cooldown-ms}") long breakerCooldownMs) {
        this.events = events;
        this.breakerCooldownMs = breakerCooldownMs;
    }

    public Instance register(String id, String url) {
        Instance existing = instances.get(id);
        if (existing != null) {
            existing.lastHeartbeat = Instant.now();
            if (!existing.url.equals(url)) {
                existing.url = url;
                events.add("REGISTRY", "Instance " + id + " changed address to " + url);
            } else if (existing.status == Instance.Status.DOWN) {
                events.add("REGISTRY", "Instance " + id + " re-appeared (heartbeat), waiting for health check");
            }
            return existing;
        }
        Instance i = new Instance(id, url);
        instances.put(id, i);
        events.add("REGISTRY", "Instance " + id + " registered at " + url);
        return i;
    }

    public boolean deregister(String id) {
        boolean removed = instances.remove(id) != null;
        if (removed) events.add("REGISTRY", "Instance " + id + " deregistered");
        return removed;
    }

    public Instance get(String id) {
        return instances.get(id);
    }

    public List<Instance> all() {
        return instances.values().stream().sorted(Comparator.comparing(i -> i.id)).toList();
    }

    public List<Instance> available() {
        return all().stream().filter(this::isAvailable).toList();
    }

    /** UP and (breaker closed, or open but cooled down so a trial request may go through). */
    protected boolean isAvailable(Instance i) {
        if (i.status != Instance.Status.UP) return false;
        return switch (i.breaker) {
            case CLOSED -> true;
            case OPEN -> System.currentTimeMillis() >= i.breakerOpenedAt.toEpochMilli() + breakerCooldownMs;
            case HALF_OPEN -> !i.trialInFlight.get();
        };
    }

    /** Round-robin over the available instances, skipping the ones already tried for this request. */
    public synchronized Instance pick(Set<String> excluded) {
        List<Instance> candidates = available().stream().filter(i -> !excluded.contains(i.id)).toList();
        if (candidates.isEmpty()) return null;
        int idx = Math.floorMod(rr.getAndIncrement(), candidates.size());
        Instance chosen = candidates.get(idx);
        if (chosen.breaker == Instance.Breaker.OPEN) {           // cooldown elapsed -> let ONE trial through
            chosen.breaker = Instance.Breaker.HALF_OPEN;
            events.add("BREAKER", "Instance " + chosen.id + " circuit HALF_OPEN: sending one trial request");
        }
        if (chosen.breaker == Instance.Breaker.HALF_OPEN) chosen.trialInFlight.set(true);
        return chosen;
    }

    /** The request to this instance succeeded: close the circuit. */
    public void recordSuccess(Instance i) {
        i.served.incrementAndGet();
        if (i.breaker != Instance.Breaker.CLOSED) {
            events.add("BREAKER", "Instance " + i.id + " circuit CLOSED (trial request succeeded)");
        }
        i.breaker = Instance.Breaker.CLOSED;
        i.breakerOpenedAt = null;
        i.trialInFlight.set(false);
    }

    /** Connection error / timeout: cut the connection to this instance immediately (circuit OPEN). */
    public void recordBreakingFailure(Instance i, String reason) {
        i.failed.incrementAndGet();
        i.lastFailure = reason;
        i.breaker = Instance.Breaker.OPEN;
        i.breakerOpenedAt = Instant.now();
        i.trialInFlight.set(false);
        events.add("BREAKER", "Instance " + i.id + " circuit OPEN (" + reason + ") - no traffic for " + breakerCooldownMs + " ms");
    }

    /** HTTP 5xx answer from the instance: retry elsewhere, but the node itself is reachable. */
    public void recordSoftFailure(Instance i, String reason) {
        i.failed.incrementAndGet();
        i.lastFailure = reason;
        i.trialInFlight.set(false);
    }

    public void markDown(Instance i, String reason) {
        if (i.status != Instance.Status.DOWN) {
            i.status = Instance.Status.DOWN;
            i.downSince = Instant.now();
            events.add("HEALTH", "Instance " + i.id + " is DOWN and removed from rotation (" + reason + ")");
        }
    }

    public void markUp(Instance i) {
        i.healthFailures = 0;
        i.lastHealthOk = Instant.now();
        if (i.status != Instance.Status.UP) {
            i.status = Instance.Status.UP;
            i.downSince = null;
            events.add("HEALTH", "Instance " + i.id + " is UP again, back in rotation");
        }
    }

    public void evict(Instance i) {
        if (instances.remove(i.id, i)) events.add("REGISTRY", "Instance " + i.id + " evicted (down too long, no heartbeat)");
    }
}
