package com.pad.lab3.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stage 1: forward the request to the next instance in round-robin order. */
@Service
public class ProxyService {

    public record ProxyRequest(String method, String pathAndQuery, Map<String, String> headers, byte[] body) {}

    public record ProxyResponse(int status, Map<String, String> headers, byte[] body) {}

    private final InstanceRegistry registry;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(1)).build();
    private final Duration timeout;

    public ProxyService(InstanceRegistry registry, @Value("${gateway.proxy.request-timeout-ms}") long timeoutMs) {
        this.registry = registry;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    public ProxyResponse forward(ProxyRequest req) {
        Instance inst = registry.pick(new HashSet<>());
        if (inst == null) {
            return error(503, "no_available_instances", "No healthy Data Warehouse instance is registered");
        }
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(inst.url + req.pathAndQuery())).timeout(timeout);
            req.headers().forEach(b::header);
            b.method(req.method(), req.body().length == 0
                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(req.body()));
            HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            inst.served.incrementAndGet();
            Map<String, String> h = new LinkedHashMap<>();
            r.headers().firstValue("Content-Type").ifPresent(v -> h.put("Content-Type", v));
            r.headers().firstValue("Location").ifPresent(v -> h.put("Location", v));
            h.put("X-Served-By", inst.id);
            return new ProxyResponse(r.statusCode(), h, r.body());
        } catch (Exception e) {
            inst.failed.incrementAndGet();
            return error(502, "bad_gateway", inst.id + ": " + e.getClass().getSimpleName());
        }
    }

    static ProxyResponse error(int status, String code, String message) {
        String json = "{\"status\":" + status + ",\"error\":\"" + code + "\",\"message\":\"" + message.replace("\"", "'") + "\"}";
        return new ProxyResponse(status, Map.of("Content-Type", "application/json"), json.getBytes());
    }
}
