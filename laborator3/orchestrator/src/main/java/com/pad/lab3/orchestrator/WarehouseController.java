package com.pad.lab3.orchestrator;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * GET    /warehouses                     list (state of the API and of the database of every warehouse)
 * POST   /warehouses {name?, count?}     create one or several warehouses (API + its own database)
 * DELETE /warehouses/{name}              remove a warehouse (API + database, leaves the replica set)
 * PUT    /warehouses/{name}/api|db {state: running|stopped|frozen}   start / stop / freeze a component
 */
@RestController
@RequestMapping("/warehouses")
public class WarehouseController {

    private final WarehouseService service;

    public WarehouseController(WarehouseService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) Map<String, Object> body) {
        String name = body == null ? null : (String) body.get("name");
        int count = body != null && body.get("count") != null ? ((Number) body.get("count")).intValue() : 1;
        List<String> names = service.createAsync(name, count);
        return ResponseEntity.accepted().body(Map.of("creating", names));
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> remove(@PathVariable String name) {
        service.removeAsync(name);
        return ResponseEntity.accepted().build();
    }

    @PutMapping("/{name}/{role}")
    public ResponseEntity<Map<String, String>> state(@PathVariable String name, @PathVariable String role,
                                                     @RequestBody Map<String, String> body) {
        if (!role.equals("api") && !role.equals("db")) throw new IllegalArgumentException("role must be api or db");
        service.setState(name, role, body.get("state"));
        return ResponseEntity.ok(Map.of("warehouse", name, "component", role, "state", body.get("state")));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ResponseEntity<Map<String, String>> bad(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> missing(NoSuchElementException e) {
        return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
    }
}
