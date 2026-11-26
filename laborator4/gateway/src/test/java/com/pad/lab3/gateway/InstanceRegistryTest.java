package com.pad.lab3.gateway;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class InstanceRegistryTest {

    private final InstanceRegistry registry = new InstanceRegistry(new EventLog(), 10_000);

    private void register(String... ids) {
        for (String id : ids) registry.register(id, "http://10.0.0.1:8101");
    }

    @Test
    void roundRobinUsesEveryInstanceInTurn() {
        register("wh-1", "wh-2", "wh-3");
        List<String> picked = Stream.generate(() -> registry.pick(Set.of()).id).limit(6).toList();
        assertEquals(List.of("wh-1", "wh-2", "wh-3", "wh-1", "wh-2", "wh-3"), picked);
    }

    @Test
    void downAndAlreadyTriedInstancesAreSkipped() {
        register("wh-1", "wh-2", "wh-3");
        registry.markDown(registry.get("wh-1"), "test");
        assertEquals("wh-3", registry.pick(Set.of("wh-2")).id);
        assertNull(registry.pick(Set.of("wh-2", "wh-3")));
    }

    @Test
    void heartbeatWithNewAddressUpdatesTheInstance() {
        register("wh-1");
        registry.register("wh-1", "http://10.0.0.2:8105");
        assertEquals(1, registry.all().size());
        assertEquals("http://10.0.0.2:8105", registry.get("wh-1").url);
    }

    @Test
    void openCircuitGetsNoTrafficUntilTheCooldownThenOneTrial() {
        register("wh-1");
        Instance i = registry.get("wh-1");
        registry.recordBreakingFailure(i, "timeout");
        assertNull(registry.pick(Set.of()), "open circuit: no traffic");

        i.breakerOpenedAt = Instant.now().minusSeconds(11);
        assertSame(i, registry.pick(Set.of()));
        assertEquals(Instance.Breaker.HALF_OPEN, i.breaker);
        assertNull(registry.pick(Set.of()), "only one trial request at a time");

        registry.recordSuccess(i);
        assertEquals(Instance.Breaker.CLOSED, i.breaker);
        assertSame(i, registry.pick(Set.of()));
    }
}
