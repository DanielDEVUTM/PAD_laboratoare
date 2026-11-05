package com.pad.lab3.dw;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Service discovery client: the node announces "I'm alive, here is my address" to the gateway
 * at startup and then periodically (so it also re-appears automatically after a gateway restart
 * or after having been evicted). On graceful shutdown it deregisters itself.
 */
@Component
public class GatewayRegistration {
    private static final Logger log = LoggerFactory.getLogger(GatewayRegistration.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final String gatewayUrl;
    private final String nodeId;
    private final String selfUrl;
    private volatile boolean registered = false;

    public GatewayRegistration(@Value("${dw.gateway-url}") String gatewayUrl,
                               @Value("${dw.node-id}") String nodeId,
                               @Value("${dw.advertise-host}") String advertiseHost,
                               @Value("${server.port}") int port) throws Exception {
        this.gatewayUrl = gatewayUrl.replaceAll("/+$", "");
        this.nodeId = nodeId;
        String host = advertiseHost == null || advertiseHost.isBlank()
                ? InetAddress.getLocalHost().getHostAddress() : advertiseHost;
        this.selfUrl = "http://" + host + ":" + port;
    }

    @PostConstruct
    void init() {
        log.info("Node '{}' will advertise itself as {} to gateway {}", nodeId, selfUrl, gatewayUrl);
    }

    @Scheduled(fixedDelayString = "${dw.registration-interval-ms}", initialDelay = 500)
    void register() {
        try {
            String json = "{\"id\":\"" + nodeId + "\",\"url\":\"" + selfUrl + "\"}";
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(gatewayUrl + "/registry/instances"))
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.discarding());
            if (r.statusCode() / 100 == 2) {
                if (!registered) log.info("Registered with gateway ({})", r.statusCode());
                registered = true;
            } else {
                registered = false;
                log.warn("Gateway refused registration: HTTP {}", r.statusCode());
            }
        } catch (Exception e) {
            if (registered) log.warn("Lost gateway: {}", e.toString());
            else log.debug("Gateway not reachable yet: {}", e.toString());
            registered = false;
        }
    }

    @PreDestroy
    void deregister() {
        try {
            http.send(HttpRequest.newBuilder(URI.create(gatewayUrl + "/registry/instances/" + nodeId))
                    .timeout(Duration.ofSeconds(1)).DELETE().build(), HttpResponse.BodyHandlers.discarding());
            log.info("Deregistered from gateway");
        } catch (Exception ignored) { }
    }
}
