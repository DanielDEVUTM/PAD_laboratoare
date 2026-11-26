package com.pad.lab3.gateway;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** Service-discovery API used by the Data Warehouse nodes to register/deregister themselves. */
@RestController
@RequestMapping("/registry/instances")
public class RegistryController {

    private final InstanceRegistry registry;

    public RegistryController(InstanceRegistry registry) {
        this.registry = registry;
    }

    @PostMapping
    public ResponseEntity<?> register(@RequestBody Map<String, String> body) {
        String id = body.get("id");
        String url = body.get("url");
        if (id == null || id.isBlank() || url == null || !url.matches("https?://[^\\s/]+(:\\d+)?")) {
            return ResponseEntity.badRequest().body(Map.of("error", "body must be {\"id\":\"...\",\"url\":\"http://host:port\"}"));
        }
        Instance i = registry.register(id, url.replaceAll("/+$", ""));
        return ResponseEntity.created(URI.create("/registry/instances/" + id)).body(i.view());
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return registry.all().stream().map(Instance::view).toList();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deregister(@PathVariable String id) {
        return registry.deregister(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
