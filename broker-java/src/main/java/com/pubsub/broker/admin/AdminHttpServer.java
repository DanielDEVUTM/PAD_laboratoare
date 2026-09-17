package com.pubsub.broker.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pubsub.broker.dlq.DeadLetterQueue;
import com.pubsub.broker.registry.TopicRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Read-only JSON API used by the local web UI to show live broker state
 * (topics, subscriber/backlog counts, DLQ contents). Runs alongside the
 * TCP and gRPC listeners; it never mutates broker state.
 */
public class AdminHttpServer {
    private final HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();

    public AdminHttpServer(int port, TopicRegistry registry) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/topics", exchange -> respond(exchange, registry.getTopicsSnapshot()));
        server.createContext("/api/dlq", exchange -> respond(exchange, DeadLetterQueue.getEntries()));
        server.setExecutor(Executors.newCachedThreadPool());
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    private void respond(HttpExchange exchange, Object data) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        byte[] body;
        int status = 200;
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
