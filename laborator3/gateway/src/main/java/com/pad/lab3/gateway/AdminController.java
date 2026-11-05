package com.pad.lab3.gateway;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only operational endpoints used by the UI. */
@RestController
public class AdminController {

    private final InstanceRegistry registry;
    private final EventLog events;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(1)).build();

    public AdminController(InstanceRegistry registry, EventLog events) {
        this.registry = registry;
        this.events = events;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "gateway");
    }

    @GetMapping("/admin/state")
    public Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instances", registry.all().stream().map(Instance::view).toList());
        return m;
    }

    @GetMapping("/admin/events")
    public List<Map<String, Object>> events() {
        return events.recent();
    }

    /** Content of ONE specific node's database, bypassing load balancing and cache. */
    @GetMapping("/admin/instances/{id}/employees")
    public ResponseEntity<byte[]> nodeEmployees(@PathVariable String id, @RequestParam(defaultValue = "100") int limit) {
        return direct(id, "/employees?limit=" + limit);
    }

    @GetMapping("/admin/instances/{id}/db")
    public ResponseEntity<byte[]> nodeDb(@PathVariable String id) {
        return direct(id, "/admin/db");
    }

    private ResponseEntity<byte[]> direct(String id, String path) {
        Instance i = registry.get(id);
        if (i == null) return ResponseEntity.status(404).body("{\"error\":\"unknown instance\"}".getBytes());
        try {
            HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(i.url + path))
                    .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            return ResponseEntity.status(r.statusCode()).header("Content-Type", "application/json").body(r.body());
        } catch (Exception e) {
            return ResponseEntity.status(502).header("Content-Type", "application/json")
                    .body(("{\"error\":\"" + e.getClass().getSimpleName() + "\"}").getBytes());
        }
    }
}
