package com.pad.lab3.gateway;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Direct access to ONE specific Data Warehouse API, for testing:
 *   /nodes/wh-1/employees/1   ->   GET /employees/1 on wh-1 only
 * No round-robin, no cache, no retry: you talk to exactly that node. Still through the gateway port.
 */
@RestController
public class NodeController {

    private final InstanceRegistry registry;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2)).build();

    public NodeController(InstanceRegistry registry) {
        this.registry = registry;
    }

    @RequestMapping("/nodes/{id}/**")
    public ResponseEntity<byte[]> direct(HttpServletRequest request) throws Exception {
        String prefix = "/nodes/";
        String rest = request.getRequestURI().substring(prefix.length());
        int slash = rest.indexOf('/');
        String id = slash < 0 ? rest : rest.substring(0, slash);
        String path = slash < 0 ? "/" : rest.substring(slash);
        Instance inst = registry.get(id);
        if (inst == null) {
            return ResponseEntity.status(404).header("Content-Type", "application/json")
                    .body(("{\"error\":\"unknown node '" + id + "'\"}").getBytes());
        }
        String target = inst.url + path + (request.getQueryString() != null ? "?" + request.getQueryString() : "");
        byte[] body = request.getInputStream().readAllBytes();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(10));
            for (String h : new String[]{"Content-Type", "Accept", "X-Simulate-Delay-Ms"}) {
                if (request.getHeader(h) != null) b.header(h, request.getHeader(h));
            }
            b.method(request.getMethod(), body.length == 0
                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            ResponseEntity.BodyBuilder out = ResponseEntity.status(r.statusCode()).header("X-Served-By", id);
            r.headers().firstValue("Content-Type").ifPresent(v -> out.header("Content-Type", v));
            r.headers().firstValue("Location").ifPresent(v -> out.header("Location", v));
            return out.body(r.body());
        } catch (Exception e) {
            return ResponseEntity.status(502).header("Content-Type", "application/json")
                    .body(("{\"error\":\"node " + id + " unreachable: " + e.getClass().getSimpleName() + "\"}").getBytes());
        }
    }
}
