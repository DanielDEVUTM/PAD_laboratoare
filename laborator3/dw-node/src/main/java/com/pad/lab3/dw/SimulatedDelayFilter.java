package com.pad.lab3.dw;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Demo/test hook: header X-Simulate-Delay-Ms makes the node answer slowly (capped at 30 s).
 * Used to demonstrate the request priority queue and the circuit breaker of the gateway.
 */
@Component
public class SimulatedDelayFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String h = req.getHeader("X-Simulate-Delay-Ms");
        if (h != null && !req.getRequestURI().equals("/health")) {
            try {
                long ms = Math.min(Math.max(Long.parseLong(h.trim()), 0), 30_000);
                Thread.sleep(ms);
            } catch (NumberFormatException ignored) {
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        chain.doFilter(req, res);
    }
}
