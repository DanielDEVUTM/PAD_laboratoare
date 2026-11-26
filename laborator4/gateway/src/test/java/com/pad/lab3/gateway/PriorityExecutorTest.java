package com.pad.lab3.gateway;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class PriorityExecutorTest {

    @Test
    void higherPriorityOvertakesTheQueueAndEqualPrioritiesStayInOrder() throws Exception {
        PriorityExecutor executor = new PriorityExecutor(1);
        CountDownLatch busy = new CountDownLatch(1), release = new CountDownLatch(1);
        executor.submit(Priority.NORMAL, () -> { busy.countDown(); release.await(); return null; });
        assertTrue(busy.await(5, TimeUnit.SECONDS));

        List<String> order = new CopyOnWriteArrayList<>();
        CompletableFuture<?>[] queued = {
                executor.submit(Priority.LOW, () -> order.add("low")),
                executor.submit(Priority.NORMAL, () -> order.add("normal-1")),
                executor.submit(Priority.NORMAL, () -> order.add("normal-2")),
                executor.submit(Priority.HIGH, () -> order.add("high")),
        };
        release.countDown();
        CompletableFuture.allOf(queued).get(5, TimeUnit.SECONDS);

        assertEquals(List.of("high", "normal-1", "normal-2", "low"), order);
    }

    @Test
    void priorityHeaderIsParsedWithNormalAsDefault() {
        assertEquals(Priority.HIGH, Priority.parse(" High "));
        assertEquals(Priority.LOW, Priority.parse("0"));
        assertEquals(Priority.NORMAL, Priority.parse(null));
        assertEquals(Priority.NORMAL, Priority.parse("urgent"));
    }
}
