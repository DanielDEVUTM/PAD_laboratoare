package com.pad.lab3.gateway;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catch-all: everything that is not a gateway endpoint (/registry, /admin, /health) is a client
 * request for the Data Warehouse and is proxied transparently. The client never learns how many
 * instances exist or where they are.
 */
@RestController
public class ProxyController {

    /** Headers that are forwarded to the instance (the JDK HttpClient forbids setting some others). */
    private static final List<String> FORWARDED = List.of("Content-Type", "Accept", "Authorization", "X-Simulate-Delay-Ms");

    private final ProxyService proxy;

    public ProxyController(ProxyService proxy) {
        this.proxy = proxy;
    }

    @RequestMapping("/**")
    public ResponseEntity<byte[]> handle(HttpServletRequest request) throws IOException {
        String pathAndQuery = request.getRequestURI() + (request.getQueryString() != null ? "?" + request.getQueryString() : "");
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : FORWARDED) {
            String v = request.getHeader(name);
            if (v != null) headers.put(name, v);
        }
        byte[] body = request.getInputStream().readAllBytes();
        ProxyService.ProxyResponse r = proxy.forward(new ProxyService.ProxyRequest(request.getMethod(), pathAndQuery, headers, body,
                Priority.parse(request.getHeader("X-Priority")),
                String.valueOf(request.getHeader("Cache-Control")).toLowerCase().contains("no-cache")));
        HttpHeaders out = new HttpHeaders();
        r.headers().forEach(out::set);
        return new ResponseEntity<>(r.body(), out, r.status());
    }
}
