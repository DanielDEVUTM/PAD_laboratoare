package com.pad.lab3.gateway;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Self-test of the priority queue. A browser cannot saturate the gateway (max ~6 connections per
 * origin), so the burst is generated here, inside the gateway, through the real proxy pipeline:
 * N slow NORMAL requests are queued, then one HIGH request is sent. The result shows at which
 * position the HIGH request finished.
 */
@RestController
public class PriorityTestController {

    record Row(String priority, int position, long ms) {}

    private final ProxyService proxy;

    public PriorityTestController(ProxyService proxy) {
        this.proxy = proxy;
    }

    @PostMapping("/admin/priority-tests")
    public Map<String, Object> run(@RequestParam(defaultValue = "30") int normal,
                                   @RequestParam(defaultValue = "300") int delayMs) throws Exception {
        int n = Math.min(Math.max(normal, 1), 200);
        int delay = Math.min(Math.max(delayMs, 0), 2000);
        AtomicInteger finished = new AtomicInteger();
        long start = System.nanoTime();
        List<Future<Row>> futures = new ArrayList<>();
        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) futures.add(callers.submit(() -> send(Priority.NORMAL, delay, start, finished)));
            Thread.sleep(100);   // let the normal requests fill the queue first
            futures.add(callers.submit(() -> send(Priority.HIGH, delay, start, finished)));
        }
        List<Row> rows = new ArrayList<>();
        for (Future<Row> f : futures) rows.add(f.get());
        Row high = rows.stream().filter(r -> r.priority().equals("HIGH")).findFirst().orElseThrow();
        long lastNormal = rows.stream().filter(r -> r.priority().equals("NORMAL")).mapToLong(Row::ms).max().orElse(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("normalRequests", n);
        out.put("delayPerRequestMs", delay);
        out.put("highFinishedPosition", high.position());
        out.put("highFinishedAfterMs", high.ms());
        out.put("lastNormalFinishedAfterMs", lastNormal);
        out.put("total", rows.size());
        return out;
    }

    private Row send(Priority p, int delay, long start, AtomicInteger counter) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-Simulate-Delay-Ms", String.valueOf(delay));
        proxy.forward(new ProxyService.ProxyRequest("GET", "/employees?limit=1", h, new byte[0], p, true));
        return new Row(p.name(), counter.incrementAndGet(), (System.nanoTime() - start) / 1_000_000);
    }
}
