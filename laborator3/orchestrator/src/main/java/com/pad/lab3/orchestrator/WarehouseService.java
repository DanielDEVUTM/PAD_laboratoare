package com.pad.lab3.orchestrator;

import com.github.dockerjava.api.model.Container;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * A "warehouse" = one Data Warehouse API container + ITS OWN database, and that database is a
 * MongoDB replica set of 3 members: 1 primary + 2 secondaries ("1 + 2"). If the primary dies the two
 * others elect a new master. The data is kept identical between warehouses by the sync service.
 *
 *   wh-1-api        the API (registers itself in the gateway)
 *   wh-1-db-1/2/3   the three members of replica set rs-wh-1
 */
@Service
public class WarehouseService {
    private static final Logger log = LoggerFactory.getLogger(WarehouseService.class);
    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]{0,18}$");
    static final int DB_MEMBERS = 3;
    static final String PHASE_SYNC = "synchronising data";

    private final DockerOps docker;
    private final String dwImage, mongoImage, gatewayUrl, syncUrl;
    private final int initial;
    private final String seedScript;
    private final Map<String, String> phases = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public WarehouseService(DockerOps docker,
                            @Value("${orchestrator.dw-image}") String dwImage,
                            @Value("${orchestrator.mongo-image}") String mongoImage,
                            @Value("${orchestrator.gateway-url}") String gatewayUrl,
                            @Value("${orchestrator.sync-url}") String syncUrl,
                            @Value("${orchestrator.initial-warehouses}") int initial) throws Exception {
        this.docker = docker;
        this.dwImage = dwImage;
        this.mongoImage = mongoImage;
        this.gatewayUrl = gatewayUrl;
        this.syncUrl = syncUrl.replaceAll("/+$", "");
        this.initial = initial;
        try (InputStream in = getClass().getResourceAsStream("/seed.js")) {
            this.seedScript = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @PostConstruct
    void bootstrap() {
        if (initial > 0 && docker.managed().isEmpty()) {
            log.info("No warehouse exists yet - creating {} initial ones (INITIAL_WAREHOUSES)", initial);
            List<String> names = new ArrayList<>();
            for (int i = 0; i < initial; i++) names.add(reserveName(names));
            runCreation(names, true);
        }
    }

    // ---------------------------------------------------------------- queries

    public List<Map<String, Object>> list() {
        Map<String, Map<String, Object>> byName = new TreeMap<>(WarehouseService::naturalOrder);
        Map<String, List<Map<String, Object>>> members = new HashMap<>();
        for (Container c : docker.managed()) {
            String wh = c.getLabels().get(DockerOps.LABEL_WAREHOUSE);
            String role = c.getLabels().get(DockerOps.LABEL_ROLE);
            Map<String, Object> w = byName.computeIfAbsent(wh, k -> new LinkedHashMap<>(Map.of("name", k)));
            String name = c.getNames()[0].substring(1);
            if (role.equals("api")) {
                w.put("api", Map.of("container", name, "state", simpleState(c.getState())));
            } else {
                members.computeIfAbsent(wh, k -> new ArrayList<>())
                        .add(Map.of("container", name, "state", simpleState(c.getState())));
            }
        }
        members.forEach((wh, list) -> {
            list.sort(Comparator.comparing(m -> (String) m.get("container")));
            long running = list.stream().filter(m -> m.get("state").equals("running")).count();
            String summary = running == list.size() ? "running" : running == 0 ? "stopped" : "degraded";
            byName.get(wh).put("db", Map.of("state", summary, "members", list));
        });
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
            phases.put(requestedName, "queued");
            names.add(requestedName);
        } else {
            for (int i = 0; i < Math.min(Math.max(count, 1), 10); i++) names.add(reserveName(names));
        }
        // the very first warehouse of an empty cluster must be created (and seeded) before the others
        boolean clusterWasEmpty = docker.managed().isEmpty();
        runCreation(names, clusterWasEmpty);
        return names;
    }

    private void runCreation(List<String> names, boolean sequential) {
        if (sequential) {
            pool.submit(() -> {
                for (String n : names) createSafely(n);
            });
        } else {
            for (String n : names) pool.submit(() -> createSafely(n));
        }
    }

    private void createSafely(String n) {
        try {
            createOne(n);
        } catch (Exception e) {
            log.error("create {} failed", n, e);
            phases.put(n, "failed: " + e.getMessage());
        }
    }

    private boolean exists(String name) {
        return phases.containsKey(name) || docker.find(name + "-api") != null || docker.find(name + "-db-1") != null;
    }

    private String reserveName(List<String> alsoTaken) {
        for (int i = 1; ; i++) {
            String n = "wh-" + i;
            if (!exists(n) && !alsoTaken.contains(n)) { phases.put(n, "queued"); return n; }
        }
    }

    private static String dbName(String wh, int i) { return wh + "-db-" + i; }

    private void createOne(String name) throws Exception {
        String rs = "rs-" + name;
        boolean first = otherWarehouses(name).isEmpty();      // only the very first warehouse is seeded
        phases.put(name, "starting 3 databases");
        docker.ensureImage(mongoImage);
        for (int i = 1; i <= DB_MEMBERS; i++) {
            docker.createAndStart(dbName(name, i), name, "db", mongoImage, null,
                    List.of("--replSet", rs, "--bind_ip_all", "--wiredTigerCacheSizeGB", "0.25"));
        }
        for (int i = 1; i <= DB_MEMBERS; i++) {
            String c = dbName(name, i);
            waitFor(() -> "1".equals(safeMongosh(c, "print(db.adminCommand({ping:1}).ok)")), 90, c + " did not start");
        }

        phases.put(name, "forming replica set (1 primary + 2 secondaries)");
        StringBuilder members = new StringBuilder();
        for (int i = 1; i <= DB_MEMBERS; i++) {
            if (i > 1) members.append(',');
            members.append("{_id:").append(i - 1).append(",host:'").append(dbName(name, i)).append(":27017'}");
        }
        String db1 = dbName(name, 1);
        docker.mongosh(db1, "print(JSON.stringify(rs.initiate({_id:'" + rs + "',members:[" + members + "]}).ok))");
        waitFor(() -> "true".equals(safeMongosh(db1, "print(db.hello().isWritablePrimary)"))
                || safeMongosh(db1, "print(db.hello().primary)").contains(":27017"), 90, "no primary elected");
        String primary = findPrimary(name, 60);
        if (first) {
            phases.put(name, "seeding initial data");
            docker.mongosh(primary, seedScript);
        }

        // the sync service copies the data of the other warehouses into this one (and starts keeping them identical)
        phases.put(name, PHASE_SYNC);
        waitFor(() -> syncReady(name), 180, "sync service did not finish the initial synchronisation");

        phases.put(name, "starting API");
        docker.ensureImage(dwImage);
        StringBuilder hosts = new StringBuilder();
        for (int i = 1; i <= DB_MEMBERS; i++) {
            if (i > 1) hosts.append(',');
            hosts.append(dbName(name, i)).append(":27017");
        }
        docker.createAndStart(name + "-api", name, "api", dwImage, List.of(
                "NODE_ID=" + name,
                "GATEWAY_URL=" + gatewayUrl,
                "MONGO_URI=mongodb://" + hosts + "/warehouse?replicaSet=" + rs
                        + "&readPreference=secondaryPreferred&w=majority&serverSelectionTimeoutMS=5000&connectTimeoutMS=2000"), null);
        phases.remove(name);
        log.info("Warehouse {} is ready", name);
    }

    private boolean syncReady(String name) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(syncUrl + "/sync/warehouses/" + name))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 && r.body().contains("\"READY\"");
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> otherWarehouses(String except) {
        Set<String> s = new TreeSet<>();
        for (Container c : docker.managed()) s.add(c.getLabels().get(DockerOps.LABEL_WAREHOUSE));
        s.remove(except);
        return new ArrayList<>(s);
    }

    // ---------------------------------------------------------------- remove / state

    public void removeAsync(String name) {
        if (!exists(name)) throw new NoSuchElementException("warehouse '" + name + "' not found");
        phases.put(name, "removing");
        pool.submit(() -> {
            try {
                try { docker.stop(name + "-api"); } catch (Exception ignored) { }   // graceful: the node deregisters itself
                docker.remove(name + "-api");
                for (int i = 1; i <= DB_MEMBERS; i++) docker.remove(dbName(name, i));
                phases.remove(name);
                log.info("Warehouse {} removed", name);
            } catch (Exception e) {
                log.error("remove {} failed", name, e);
                phases.put(name, "failed: " + e.getMessage());
            }
        });
    }

    /** state: running | stopped | frozen. role: api, or db (all 3 members), or db + member 1..3 (one member). */
    public void setState(String name, String role, Integer member, String state) {
        List<String> targets = new ArrayList<>();
        if (role.equals("api")) targets.add(name + "-api");
        else if (member != null) targets.add(dbName(name, member));
        else for (int i = 1; i <= DB_MEMBERS; i++) targets.add(dbName(name, i));
        for (String c : targets) {
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
    }

    // ---------------------------------------------------------------- helpers

    private String findPrimary(String wh, int seconds) throws Exception {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            for (int i = 1; i <= DB_MEMBERS; i++) {
                String c = dbName(wh, i);
                if ("true".equals(safeMongosh(c, "print(db.hello().isWritablePrimary)"))) return c;
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("no PRIMARY found in rs-" + wh);
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
