package com.paygate.gateway.filter;

import com.paygate.gateway.security.JwtVerifier;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;


import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Runs first on every request. Three jobs:
 *   1. Block /internal/** completely — it must never be reachable from outside the network.
 *   2. Let a short allow-list of public routes through unauthenticated (merchant
 *      creation, token issuance, health checks).
 *   3. For everything else, require a valid "Authorization: Bearer <jwt>", verify it,
 *      and replace X-Merchant-Id with the verified merchant id from the token —
 *      a client-supplied X-Merchant-Id header is ALWAYS stripped first, so a caller
 *      can never claim to be a merchant it doesn't have a key for.
 *
 * Downstream services (payment-service) trust X-Merchant-Id only because
 * nothing but the Gateway can reach them directly (see docker-compose.yml —
 * payment-service no longer publishes a host port).
 */
@Component
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/api/v1/auth/token",
            "/api/v1/merchants",
            "/actuator/health",
            "/actuator/prometheus"
    );

    private final JwtVerifier jwtVerifier;

    public JwtAuthGlobalFilter(JwtVerifier jwtVerifier) {
        this.jwtVerifier = jwtVerifier;
    }

    @Override
    public int getOrder() {
        return -100; // run before the rate limiter, which needs the verified merchant id
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        if (path.startsWith("/internal/")) {
            return reject(exchange, HttpStatus.FORBIDDEN, "Internal endpoints are not reachable through the gateway");
        }

        if (isPublic(path)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "Missing or malformed Authorization header");
        }

        String token = authHeader.substring("Bearer ".length());
        String merchantId;
        try {
            merchantId = jwtVerifier.verifyAndGetSubject(token);
        } catch (JwtVerifier.JwtInvalidException e) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, e.getMessage());
        }

        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove("X-Merchant-Id"); // never trust a client-supplied value
                    headers.set("X-Merchant-Id", merchantId);
                })
                .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    private boolean isPublic(String path) {
        return PUBLIC_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().add("Content-Type", "application/json");
        String body = "{\"status\":%d,\"detail\":\"%s\"}".formatted(status.value(), message);
        return response.writeWith(Mono.just(
                response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }
}
