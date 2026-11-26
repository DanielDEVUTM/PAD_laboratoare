package com.pad.lab3.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.*;
import com.mongodb.client.model.*;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.FullDocument;
import org.bson.BsonDocument;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Synchronisation microservice.
 *
 * Every warehouse has its own MongoDB replica set (replication INSIDE a warehouse is MongoDB's own).
 * To keep the data identical BETWEEN warehouses, this service listens to the change stream of every
 * warehouse and applies each insert/update/delete to all the others.
 *
 *  - last-writer-wins on the "updatedAt" field; applying an identical document is a no-op in MongoDB,
 *    so an applied change does not bounce back and forth forever;
 *  - a new warehouse is first merged with the existing ones (full copy) and only then reported READY
 *    (the orchestrator starts its API only after that);
 *  - changes that cannot be applied (target database down) are queued in order and retried.
 */
@Component
public class SyncManager {
    private static final Logger log = LoggerFactory.getLogger(SyncManager.class);

    public enum State { BOOTSTRAPPING, READY }

    record Op(boolean delete, String id, Document doc) {}

    class Wh {
        final String name;
        final MongoClient client;
        final MongoCollection<Document> coll;
        volatile State state = State.BOOTSTRAPPING;
        volatile boolean running = true;
        volatile String lastError;
        volatile BsonDocument resumeToken;
        final LinkedBlockingDeque<Op> pending = new LinkedBlockingDeque<>();
        final AtomicLong applied = new AtomicLong();
        final CountDownLatch watching = new CountDownLatch(1);

        Wh(String name, List<String> hosts) {
            this.name = name;
            String uri = "mongodb://" + String.join(",", hosts) + "/?replicaSet=rs-" + name
                    + "&serverSelectionTimeoutMS=3000&connectTimeoutMS=2000";
            this.client = MongoClients.create(uri);
            this.coll = client.getDatabase("warehouse").getCollection("employees");
        }

        void close() {
            running = false;
            try { client.close(); } catch (Exception ignored) { }
        }
    }

    private final Map<String, Wh> whs = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String orchestratorUrl;

    public SyncManager(@Value("${sync.orchestrator-url}") String orchestratorUrl) {
        this.orchestratorUrl = orchestratorUrl.replaceAll("/+$", "");
    }

    // ------------------------------------------------------------ discovery of warehouses

    @Scheduled(fixedDelayString = "${sync.discovery-interval-ms}", initialDelay = 2000)
    void discover() {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(orchestratorUrl + "/warehouses"))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return;
            Set<String> present = new HashSet<>();
            for (JsonNode w : json.readTree(r.body())) {
                String name = w.get("name").asText();
                String phase = w.path("phase").asText("ready");
                List<String> hosts = new ArrayList<>();
                for (JsonNode m : w.path("db").path("members")) hosts.add(m.get("container").asText() + ":27017");
                if (hosts.isEmpty()) continue;
                present.add(name);
                // register once the replica set of the warehouse exists (orchestrator phase "synchronising data" or later)
                if (!whs.containsKey(name) && (phase.equals("synchronising data") || phase.equals("ready"))) {
                    register(name, hosts);
                }
            }
            for (String name : new ArrayList<>(whs.keySet())) {
                if (!present.contains(name)) unregister(name);
            }
        } catch (Exception e) {
            log.debug("discovery failed: {}", e.toString());
        }
    }

    private void register(String name, List<String> hosts) {
        Wh w = new Wh(name, hosts);
        whs.put(name, w);
        log.info("Warehouse {} registered for synchronisation ({})", name, hosts);
        pool.submit(() -> {
            try {
                bootstrap(w);
            } catch (Exception e) {
                log.error("bootstrap of {} failed: {}", name, e.toString());
                w.lastError = e.toString();
                whs.remove(name, w);
                w.close();
            }
        });
    }

    private void unregister(String name) {
        Wh w = whs.remove(name);
        if (w != null) {
            w.close();
            log.info("Warehouse {} removed from synchronisation", name);
        }
    }

    // ------------------------------------------------------------ bootstrap

    private void bootstrap(Wh w) throws Exception {
        long end = System.currentTimeMillis() + 180_000;
        while (true) {   // wait for a PRIMARY in the warehouse's replica set
            try {
                Document hello = w.client.getDatabase("admin").runCommand(new Document("hello", 1));
                if (Boolean.TRUE.equals(hello.getBoolean("isWritablePrimary"))) break;
            } catch (Exception ignored) { }
            if (System.currentTimeMillis() > end) throw new IllegalStateException("no PRIMARY in rs-" + w.name);
            Thread.sleep(1000);
        }
        pool.submit(() -> applierLoop(w));
        pool.submit(() -> watcherLoop(w));
        if (!w.watching.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("change stream did not open");
        // full merge in both directions with every warehouse that is already in sync
        for (Wh o : whs.values()) {
            if (o != w && o.state == State.READY) {
                int a = merge(o, w), b = merge(w, o);
                log.info("Initial merge {} <-> {}: {} copied in, {} copied out", o.name, w.name, a, b);
            }
        }
        w.state = State.READY;
        log.info("Warehouse {} is READY", w.name);
    }

    /** Copies every document of src that is newer than (or missing in) dst. */
    private int merge(Wh src, Wh dst) {
        int count = 0;
        List<WriteModel<Document>> batch = new ArrayList<>();
        for (Document d : src.coll.find()) {
            batch.add(new ReplaceOneModel<>(filterFor(d), d, new ReplaceOptions().upsert(true)));
            count++;
            if (batch.size() >= 500) { flush(dst, batch); batch.clear(); }
        }
        if (!batch.isEmpty()) flush(dst, batch);
        return count;
    }

    private void flush(Wh dst, List<WriteModel<Document>> batch) {
        try {
            dst.coll.bulkWrite(batch, new BulkWriteOptions().ordered(false));
        } catch (MongoBulkWriteException e) {
            // duplicate key = target already holds a newer version: expected, ignore
            if (e.getWriteErrors().stream().anyMatch(x -> x.getCode() != 11000)) throw e;
        }
    }

    /** Last-writer-wins: only replace when the stored version is not newer. */
    private static org.bson.conversions.Bson filterFor(Document d) {
        Object ts = d.get("updatedAt");
        return ts == null ? Filters.eq("_id", d.get("_id"))
                : Filters.and(Filters.eq("_id", d.get("_id")), Filters.lte("updatedAt", ts));
    }

    // ------------------------------------------------------------ change stream -> queues

    private void watcherLoop(Wh w) {
        while (w.running) {
            try {
                ChangeStreamIterable<Document> it = w.coll.watch().fullDocument(FullDocument.UPDATE_LOOKUP)
                        .maxAwaitTime(1, TimeUnit.SECONDS);
                if (w.resumeToken != null) it = it.resumeAfter(w.resumeToken);
                try (MongoChangeStreamCursor<ChangeStreamDocument<Document>> cur = it.cursor()) {
                    w.watching.countDown();
                    while (w.running) {
                        ChangeStreamDocument<Document> ev = cur.tryNext();
                        if (ev == null) continue;
                        w.resumeToken = ev.getResumeToken();
                        Op op = toOp(ev);
                        if (op != null) {
                            for (Wh o : whs.values()) {
                                if (o != w && o.pending.size() < 100_000) o.pending.add(op);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                if (!w.running) return;
                w.lastError = e.toString();
                log.warn("change stream of {} interrupted: {}", w.name, e.toString());
                if (e.toString().contains("ChangeStreamHistoryLost")) w.resumeToken = null;
                sleep(2000);
            }
        }
    }

    private static Op toOp(ChangeStreamDocument<Document> ev) {
        if (ev.getOperationType() == null || ev.getDocumentKey() == null) return null;
        switch (ev.getOperationType()) {
            case INSERT, UPDATE, REPLACE -> {
                Document d = ev.getFullDocument();
                return d == null ? null : new Op(false, String.valueOf(d.get("_id")), d);
            }
            case DELETE -> {
                return new Op(true, ev.getDocumentKey().getString("_id").getValue(), null);
            }
            default -> {
                return null;
            }
        }
    }

    // ------------------------------------------------------------ queue -> target database

    private void applierLoop(Wh w) {
        while (w.running) {
            Op op = null;
            try {
                op = w.pending.poll(1, TimeUnit.SECONDS);
                if (op == null) continue;
                if (op.delete()) {
                    w.coll.deleteOne(Filters.eq("_id", op.id()));
                } else {
                    try {
                        w.coll.replaceOne(filterFor(op.doc()), op.doc(), new ReplaceOptions().upsert(true));
                    } catch (MongoWriteException e) {
                        if (e.getError().getCode() != 11000) throw e;   // target already has a newer version
                    }
                }
                w.applied.incrementAndGet();
                w.lastError = null;
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                if (!w.running) return;
                if (op != null) w.pending.addFirst(op);      // keep the order, retry later
                w.lastError = e.toString();
                sleep(2000);
            }
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ------------------------------------------------------------ status

    public List<Map<String, Object>> status() {
        List<Map<String, Object>> out = new ArrayList<>();
        new TreeMap<>(whs).forEach((n, w) -> out.add(view(w)));
        return out;
    }

    public Map<String, Object> status(String name) {
        Wh w = whs.get(name);
        return w == null ? null : view(w);
    }

    private Map<String, Object> view(Wh w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", w.name);
        m.put("state", w.state.name());
        m.put("pendingChanges", w.pending.size());
        m.put("appliedChanges", w.applied.get());
        m.put("lastError", w.lastError);
        return m;
    }
}
