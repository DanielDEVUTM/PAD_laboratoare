package com.pad.lab3.sync;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/sync")
public class SyncController {
    private final SyncManager manager;

    public SyncController(SyncManager manager) {
        this.manager = manager;
    }

    @GetMapping("/warehouses")
    public List<Map<String, Object>> all() {
        return manager.status();
    }

    @GetMapping("/warehouses/{name}")
    public ResponseEntity<Map<String, Object>> one(@PathVariable String name) {
        Map<String, Object> s = manager.status(name);
        return s == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(s);
    }
}
