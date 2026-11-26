package com.pad.lab3.gateway;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * /warehouses/** is the management API (create / remove / stop warehouses) served by the orchestrator,
 * /sync/** is the status of the synchronisation service. Clients still talk only to the gateway.
 */
@RestController
public class OrchestratorController {

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2)).build();
    private final String orchestratorUrl;
    private final String syncUrl;

    public OrchestratorController(@Value("${gateway.orchestrator-url}") String orchestratorUrl,
                                  @Value("${gateway.sync-url}") String syncUrl) {
        this.orchestratorUrl = orchestratorUrl.replaceAll("/+$", "");
        this.syncUrl = syncUrl.replaceAll("/+$", "");
    }

    @RequestMapping({"/warehouses", "/warehouses/**", "/sync", "/sync/**"})
    public ResponseEntity<byte[]> forward(HttpServletRequest request) throws Exception {
        String base = request.getRequestURI().startsWith("/sync") ? syncUrl : orchestratorUrl;
        String target = base + request.getRequestURI()
                + (request.getQueryString() != null ? "?" + request.getQueryString() : "");
        byte[] body = request.getInputStream().readAllBytes();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(120));
            if (request.getContentType() != null) b.header("Content-Type", request.getContentType());
            b.method(request.getMethod(), body.length == 0
                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            return ResponseEntity.status(r.statusCode())
                    .header("Content-Type", r.headers().firstValue("Content-Type").orElse("application/json"))
                    .body(r.body());
        } catch (Exception e) {
            return ResponseEntity.status(503).header("Content-Type", "application/json")
                    .body("{\"error\":\"orchestrator_unavailable\"}".getBytes());
        }
    }
}
