package com.pad.lab3.orchestrator;

import com.github.dockerjava.api.model.Container;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * A "warehouse" = one Data Warehouse API container + its own MongoDB container.
 * Creating a warehouse starts a new MongoDB member, joins it to the replica set (MongoDB then
 * copies all data to it and keeps it in sync) and starts a new API node that registers itself
 * in the gateway. Removing a warehouse does the reverse.
 */
@Service
public class WarehouseService {
    private static final Logger log = LoggerFactory.getLogger(WarehouseService.class);
    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]{0,18}$");
    private static final String RS = "rs0";

    private final DockerOps docker;
    private final String dwImage, mongoImage, gatewayUrl;
    private final int initial;
    private final String seedScript;
    private final Object rsLock = new Object();                 // replica-set reconfigurations are serialised
    private final Map<String, String> phases = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();

    public WarehouseService(DockerOps docker,
                            @Value("${orchestrator.dw-image}") String dwImage,
                            @Value("${orchestrator.mongo-image}") String mongoImage,
                            @Value("${orchestrator.gateway-url}") String gatewayUrl,
                            @Value("${orchestrator.initial-warehouses}") int initial) throws Exception {
        this.docker = docker;
        this.dwImage = dwImage;
        this.mongoImage = mongoImage;
        this.gatewayUrl = gatewayUrl;
        this.initial = initial;
        try (InputStream in = getClass().getResourceAsStream("/seed.js")) {
            this.seedScript = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @PostConstruct
    void bootstrap() {
        if (initial > 0 && docker.managed().isEmpty()) {
            log.info("No warehouse exists yet - creating {} initial ones (INITIAL_WAREHOUSES)", initial);
            pool.submit(() -> {
                for (int i = 0; i < initial; i++) {
                    try { createOne(nextName()); } catch (Exception e) { log.error("bootstrap failed", e); }
                }
            });
        }
    }

    // ---------------------------------------------------------------- queries

    public List<Map<String, Object>> list() {
        Map<String, Map<String, Object>> byName = new TreeMap<>(WarehouseService::naturalOrder);
        for (Container c : docker.managed()) {
            String wh = c.getLabels().get(DockerOps.LABEL_WAREHOUSE);
            String role = c.getLabels().get(DockerOps.LABEL_ROLE);
            Map<String, Object> w = byName.computeIfAbsent(wh, k -> new LinkedHashMap<>(Map.of("name", k)));
            w.put(role, Map.of("container", c.getNames()[0].substring(1), "state", simpleState(c.getState())));
        }
        phases.keySet().forEach(n -> byName.computeIfAbsent(n, k -> new LinkedHashMap<>(Map.of("name", k))));
        byName.forEach((n, w) -> w.put("phase", phases.getOrDefault(n, "ready")));
        return new ArrayList<>(byName.values());
    }

    private static String simpleState(String s) {
        return switch (s) {
            case "running" -> "running";
            case "paused" -> "frozen";
            default -> "stopped";
        };
    }

    private static int naturalOrder(String a, String b) {
        try {
            return Integer.compare(Integer.parseInt(a.replaceAll("\\D+", "")), Integer.parseInt(b.replaceAll("\\D+", "")));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }

    // ---------------------------------------------------------------- create

    public List<String> createAsync(String requestedName, int count) {
        List<String> names = new ArrayList<>();
        if (requestedName != null && !requestedName.isBlank()) {
            if (!NAME.matcher(requestedName).matches())
                throw new IllegalArgumentException("name must match [a-z][a-z0-9-]{0,18}");
            if (exists(requestedName)) throw new IllegalStateException("warehouse '" + requestedName + "' already exists");
            names.add(requestedName);
        } else {
            for (int i = 0; i < Math.min(Math.max(count, 1), 10); i++) names.add(reserveName(names));
        }
        for (String n : names) phases.put(n, "queued");
        for (String n : names) pool.submit(() -> {
            try { createOne(n); } catch (Exception e) {
                log.error("create {} failed", n, e);
                phases.put(n, "failed: " + e.getMessage());
            }
        });
        return names;
    }

    private boolean exists(String name) {
        return phases.containsKey(name) || docker.find(name + "-api") != null || docker.find(name + "-db") != null;
    }

    private String nextName() { return reserveName(List.of()); }

    private String reserveName(List<String> alsoTaken) {
        for (int i = 1; ; i++) {
            String n = "wh-" + i;
            if (!exists(n) && !alsoTaken.contains(n)) { phases.put(n, "queued"); return n; }
        }
    }

    private void createOne(String name) throws Exception {
        String dbName = name + "-db", apiName = name + "-api", host = dbName + ":27017";
        phases.put(name, "starting database");
        docker.ensureImage(mongoImage);
        docker.createAndStart(dbName, name, "db", mongoImage, null, List.of("--replSet", RS, "--bind_ip_all"));
        waitFor(() -> "1".equals(safeMongosh(dbName, "print(db.adminCommand({ping:1}).ok)")), 90, "MongoDB did not start");

        synchronized (rsLock) {
            phases.put(name, "joining replica set");
            List<String> others = runningDbs(dbName);
            if (others.isEmpty()) {
                docker.mongosh(dbName, "print(JSON.stringify(rs.initiate({_id:'" + RS + "',members:[{_id:0,host:'" + host + "'}]}).ok))");
                waitFor(() -> "true".equals(safeMongosh(dbName, "print(db.hello().isWritablePrimary)")), 60, "no primary elected");
                phases.put(name, "seeding initial data");
                docker.mongosh(dbName, seedScript);
            } else {
                String primary = findPrimary(others, 90);
                docker.mongosh(primary,
                        "const c=rs.conf();const id=Math.max(...c.members.map(m=>m._id))+1;"
                                + "const voting=c.members.filter(m=>m.votes>0).length;"      // MongoDB allows max 7 voting members
                                + "print(JSON.stringify(rs.add(voting>=7?{_id:id,host:'" + host + "',votes:0,priority:0}:{_id:id,host:'" + host + "'}).ok))");
                phases.put(name, "synchronising data (initial sync)");
                String p = primary;
                waitFor(() -> "SECONDARY".equals(safeMongosh(p,
                        "const m=rs.status().members.find(x=>x.name==='" + host + "');print(m?m.stateStr:'none')"))
                        || "PRIMARY".equals(safeMongosh(p, "const m=rs.status().members.find(x=>x.name==='" + host + "');print(m?m.stateStr:'none')")), 180, "member did not finish initial sync");
            }
        }

        phases.put(name, "starting API");
        docker.ensureImage(dwImage);
        docker.createAndStart(apiName, name, "api", dwImage, List.of(
                "NODE_ID=" + name,
                "GATEWAY_URL=" + gatewayUrl,
                // seed with its own database; the driver discovers the other replica-set members
                "MONGO_URI=mongodb://" + host + "/warehouse?replicaSet=" + RS
                        + "&readPreference=secondaryPreferred&w=majority&serverSelectionTimeoutMS=5000&connectTimeoutMS=2000"), null);
        phases.remove(name);
        log.info("Warehouse {} is ready", name);
    }

    // ---------------------------------------------------------------- remove / state

    public void removeAsync(String name) {
        if (!exists(name)) throw new NoSuchElementException("warehouse '" + name + "' not found");
        phases.put(name, "removing");
        pool.submit(() -> {
            try { removeOne(name); } catch (Exception e) {
                log.error("remove {} failed", name, e);
                phases.put(name, "failed: " + e.getMessage());
            }
        });
    }

    private void removeOne(String name) throws Exception {
        String dbName = name + "-db", host = dbName + ":27017";
        try { docker.stop(name + "-api"); } catch (Exception ignored) { }   // graceful stop: the node deregisters itself
        docker.remove(name + "-api");
        synchronized (rsLock) {
            Container db = docker.find(dbName);
            List<String> others = runningDbs(dbName);
            if (db != null && !others.isEmpty()) {
                try {
                    String primary = findPrimary(others, 60);
                    docker.mongosh(primary, "try{rs.remove('" + host + "')}catch(e){print(e)}");
                } catch (Exception e) {
                    // the member being removed may be the primary: step it down so another one is elected
                    safeMongosh(dbName, "try{rs.stepDown(60)}catch(e){}");
                    Thread.sleep(3000);
                    String primary = findPrimary(others, 60);
                    docker.mongosh(primary, "try{rs.remove('" + host + "')}catch(e){print(e)}");
                }
            }
        }
        docker.remove(dbName);
        phases.remove(name);
        log.info("Warehouse {} removed", name);
    }

    /** state: running | stopped | frozen, for role api or db. */
    public void setState(String name, String role, String state) {
        String c = name + "-" + role;
        Container cur = docker.find(c);
        if (cur == null) throw new NoSuchElementException(c + " not found");
        String s = cur.getState();
        switch (state) {
            case "running" -> { if (s.equals("paused")) docker.unpause(c); else if (!s.equals("running")) docker.start(c); }
            case "stopped" -> { if (s.equals("paused")) docker.unpause(c); if (!s.equals("exited")) docker.stop(c); }
            case "frozen" -> { if (s.equals("running")) docker.pause(c); }
            default -> throw new IllegalArgumentException("state must be running, stopped or frozen");
        }
    }

    // ---------------------------------------------------------------- helpers

    private List<String> runningDbs(String except) {
        return docker.managed().stream()
                .filter(c -> "db".equals(c.getLabels().get(DockerOps.LABEL_ROLE)) && "running".equals(c.getState()))
                .map(c -> c.getNames()[0].substring(1)).filter(n -> !n.equals(except)).toList();
    }

    private String findPrimary(List<String> candidates, int seconds) throws Exception {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            for (String c : candidates) {
                if ("true".equals(safeMongosh(c, "print(db.hello().isWritablePrimary)"))) return c;
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("no PRIMARY found in the replica set");
    }

    private String safeMongosh(String container, String script) {
        try { return docker.mongosh(container, script); } catch (Exception e) { return ""; }
    }

    private interface Condition { boolean ok(); }

    private void waitFor(Condition c, int seconds, String message) throws Exception {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            if (c.ok()) return;
            Thread.sleep(1000);
        }
        throw new IllegalStateException(message);
    }
}
