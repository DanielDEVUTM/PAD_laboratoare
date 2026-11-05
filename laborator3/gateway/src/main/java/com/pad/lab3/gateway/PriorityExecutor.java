package com.pad.lab3.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded pool of "upstream workers" fed by a PriorityBlockingQueue.
 * The number of concurrent upstream calls is limited (gateway.proxy.workers); when all workers are
 * busy, waiting requests are ordered by priority first (HIGH before NORMAL before LOW) and by
 * arrival order inside the same priority. So a HIGH request overtakes everything that is queued.
 */
@Component
public class PriorityExecutor {

    private final ThreadPoolExecutor pool;
    private final AtomicLong seq = new AtomicLong();
    private final int workers;
    private final Map<Priority, AtomicLong> completed = new EnumMap<>(Priority.class);

    public PriorityExecutor(@Value("${gateway.proxy.workers}") int workers) {
        this.workers = workers;
        for (Priority p : Priority.values()) completed.put(p, new AtomicLong());
        this.pool = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.SECONDS, new PriorityBlockingQueue<>(64),
                r -> {
                    Thread t = new Thread(r, "upstream-worker");
                    t.setDaemon(true);
                    return t;
                });
    }

    public <T> CompletableFuture<T> submit(Priority priority, Callable<T> work) {
        PriorityTask<T> task = new PriorityTask<>(priority, seq.getAndIncrement(), work);
        pool.execute(task);   // execute (not submit) so the queue sees our Comparable task
        return task.future;
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("workers", workers);
        m.put("active", pool.getActiveCount());
        m.put("queued", pool.getQueue().size());
        Map<String, Long> done = new LinkedHashMap<>();
        completed.forEach((p, c) -> done.put(p.name(), c.get()));
        m.put("completedByPriority", done);
        return m;
    }

    private class PriorityTask<T> implements Runnable, Comparable<PriorityTask<?>> {
        final Priority priority;
        final long order;
        final Callable<T> work;
        final CompletableFuture<T> future = new CompletableFuture<>();

        PriorityTask(Priority priority, long order, Callable<T> work) {
            this.priority = priority;
            this.order = order;
            this.work = work;
        }

        @Override
        public void run() {
            if (future.isCancelled()) return;
            try {
                future.complete(work.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            } finally {
                completed.get(priority).incrementAndGet();
            }
        }

        @Override
        public int compareTo(PriorityTask<?> o) {
            int c = Integer.compare(o.priority.weight, priority.weight);   // higher weight first
            return c != 0 ? c : Long.compare(order, o.order);               // then FIFO
        }
    }
}
