package com.pad.lab3.dw;

import com.mongodb.ReadPreference;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class HealthController {

    private final MongoTemplate mongo;
    private final String nodeId;

    public HealthController(MongoTemplate mongo, @Value("${dw.node-id}") String nodeId) {
        this.mongo = mongo;
        this.nodeId = nodeId;
    }

    /** Polled by the gateway every second (health check). */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("node", nodeId);
        try {
            mongo.getDb().runCommand(new Document("ping", 1), ReadPreference.secondaryPreferred());
            body.put("status", "UP");
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            body.put("status", "DOWN");
            body.put("reason", e.getClass().getSimpleName());
            return ResponseEntity.status(503).body(body);
        }
    }

    /** What the DB behind this node looks like: replica-set members, roles, document count. */
    @GetMapping("/admin/db")
    public Map<String, Object> db() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("node", nodeId);
        try {
            Document status = mongo.getMongoDatabaseFactory().getMongoDatabase("admin")
                    .withReadPreference(ReadPreference.secondaryPreferred())
                    .runCommand(new Document("replSetGetStatus", 1));
            out.put("replicaSet", status.getString("set"));
            List<Map<String, Object>> members = new ArrayList<>();
            for (Document m : status.getList("members", Document.class)) {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("name", m.getString("name"));
                mm.put("state", m.getString("stateStr"));
                mm.put("health", m.get("health"));
                members.add(mm);
            }
            out.put("members", members);
        } catch (Exception e) {
            out.put("replicaSetError", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        try {
            out.put("employees", mongo.getCollection("employees").countDocuments());
        } catch (Exception e) {
            out.put("employeesError", e.getClass().getSimpleName());
        }
        return out;
    }
}
