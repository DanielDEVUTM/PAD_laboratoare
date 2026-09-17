package com.pubsub.broker.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pubsub.broker.dlq.DeadLetterQueue;
import com.pubsub.broker.registry.TopicRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * JSON API used by the local web UI to show and manage live broker state
 * (topics, subscriber/backlog counts, DLQ contents). Mutating endpoints
 * (DELETE) are admin-only actions - deleting a topic disconnects everyone
 * on it, deleting a named subscriber forgets its identity and backlog.
 */
public class AdminHttpServer {
    private final HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();

    public AdminHttpServer(int port, TopicRegistry registry) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/topics", exchange -> {
            if (isPreflight(exchange)) return;
            handleTopics(exchange, registry);
        });
        server.createContext("/api/dlq", exchange -> {
            if (isPreflight(exchange)) return;
            respond(exchange, 200, DeadLetterQueue.getEntries());
        });
        server.createContext("/api/subscriber-roster", exchange -> {
            if (isPreflight(exchange)) return;
            respond(exchange, 200, registry.getIdentifiedSubscribersSnapshot());
        });
        server.setExecutor(Executors.newCachedThreadPool());
    }

    /**
     * Handles CORS preflight (OPTIONS) requests so browsers on OTHER machines/ports
     * (e.g. a teammate's Publisher/Subscriber/Broker UI running on their own laptop,
     * over a shared hotspot) are allowed to call this API directly, including DELETE
     * which browsers always preflight. Returns true if it handled the request.
     */
    private boolean isPreflight(HttpExchange exchange) throws IOException {
        if (!"OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            return false;
        }
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, DELETE, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
        return true;
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    /**
     * Handles everything under /api/topics:
     *   GET    /api/topics                              -> list all topics
     *   DELETE /api/topics/{topic}                       -> delete a whole topic
     *   DELETE /api/topics/{topic}/subscribers/{id}      -> forget one identified subscriber
     */
    private void handleTopics(HttpExchange exchange, TopicRegistry registry) throws IOException {
        String method = exchange.getRequestMethod();
        String[] parts = exchange.getRequestURI().getPath().split("/");
        // "/api/topics" -> ["", "api", "topics"]
        // "/api/topics/sport" -> ["", "api", "topics", "sport"]
        // "/api/topics/sport/subscribers/S1" -> ["", "api", "topics", "sport", "subscribers", "S1"]

        if ("GET".equalsIgnoreCase(method) && parts.length == 3) {
            respond(exchange, 200, registry.getTopicsSnapshot());
            return;
        }

        if ("DELETE".equalsIgnoreCase(method) && parts.length == 4) {
            String topic = URLDecoder.decode(parts[3], StandardCharsets.UTF_8);
            registry.removeTopic(topic);
            respond(exchange, 200, Map.of("status", "ok", "topic", topic));
            return;
        }

        if ("DELETE".equalsIgnoreCase(method) && parts.length == 6 && "subscribers".equals(parts[4])) {
            String topic = URLDecoder.decode(parts[3], StandardCharsets.UTF_8);
            String subscriberId = URLDecoder.decode(parts[5], StandardCharsets.UTF_8);
            boolean removed = registry.forgetIdentifiedSubscriber(topic, subscriberId);
            respond(exchange, removed ? 200 : 404,
                    Map.of("status", removed ? "ok" : "error", "topic", topic, "subscriberId", subscriberId));
            return;
        }

        respond(exchange, 404, Map.of("error", "Rută necunoscută"));
    }

    private void respond(HttpExchange exchange, int status, Object data) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, DELETE, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");

        byte[] body;
        try {
            body = mapper.writeValueAsBytes(data);
        } catch (Exception e) {
            body = ("{\"error\":\"" + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
            status = 500;
        }

        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
