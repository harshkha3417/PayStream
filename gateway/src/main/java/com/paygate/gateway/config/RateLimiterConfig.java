package com.paygate.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Supplies the key the Redis-backed RequestRateLimiter filter (configured in
 * application.yml, one per route) uses to track usage. Reads X-Merchant-Id,
 * which by the time this runs has ALREADY been verified and set by
 * JwtAuthGlobalFilter (order -100, always runs first on every request) — this
 * never trusts a client-supplied value, only the gateway's own verified one.
 *
 * Falls back to the caller's IP for the handful of public, unauthenticated
 * routes (merchant creation, token issuance) that have no merchant id yet —
 * still useful as basic brute-force protection on those endpoints.
 *
 * Why Redis now instead of Week 2's in-memory counter: with more than one
 * gateway instance, an in-memory counter is wrong by construction — each
 * replica has its own count, so a merchant effectively gets N times the
 * limit with N replicas. Spring Cloud Gateway's built-in RedisRateLimiter
 * uses a Lua script executed atomically on Redis (a sliding-window token
 * bucket), so every replica shares one true count.
 */
@Configuration
public class RateLimiterConfig {

    @Bean
    public KeyResolver merchantKeyResolver() {
        return exchange -> {
            String merchantId = exchange.getRequest().getHeaders().getFirst("X-Merchant-Id");
            if (merchantId != null) {
                return Mono.just(merchantId);
            }
            String ip = exchange.getRequest().getRemoteAddress() != null
                    ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                    : "unknown";
            return Mono.just("ip:" + ip);
        };
    }
}
