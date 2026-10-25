package com.paygate.processor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Simulates third-party bank/card network payment processing.
 *
 * <p>Configurable latency and failure rates are used to simulate real-world asynchronous
 * payment processing behaviors. In accordance with project conventions, {@link SecureRandom}
 * is used for pseudo-random outcome decisions.</p>
 */
@Service
public class PaymentSimulator {

    private static final Logger log = LoggerFactory.getLogger(PaymentSimulator.class);

    private final long minLatencyMs;
    private final long maxLatencyMs;
    private final double failureRate;
    private final SecureRandom random;

    @Autowired
    public PaymentSimulator(
            @Value("${processor.min-latency-ms:50}") long minLatencyMs,
            @Value("${processor.max-latency-ms:500}") long maxLatencyMs,
            @Value("${processor.failure-rate:0.1}") double failureRate) {
        this(minLatencyMs, maxLatencyMs, failureRate, new SecureRandom());
    }

    public PaymentSimulator(long minLatencyMs, long maxLatencyMs, double failureRate, SecureRandom random) {
        if (minLatencyMs < 0 || maxLatencyMs < minLatencyMs) {
            throw new IllegalArgumentException("Invalid latency configuration: min=" + minLatencyMs + ", max=" + maxLatencyMs);
        }
        if (failureRate < 0.0 || failureRate > 1.0) {
            throw new IllegalArgumentException("Failure rate must be between 0.0 and 1.0, got: " + failureRate);
        }
        this.minLatencyMs = minLatencyMs;
        this.maxLatencyMs = maxLatencyMs;
        this.failureRate = failureRate;
        this.random = random;
    }

    /**
     * Simulates external bank processing: sleeps for a random duration between minLatencyMs and maxLatencyMs,
     * then determines whether the payment succeeded or failed based on failureRate.
     *
     * @param paymentId the payment aggregate ID being processed
     * @return {@code true} if simulated outcome is SUCCEEDED; {@code false} if FAILED
     */
    public boolean process(UUID paymentId) {
        long latency = minLatencyMs;
        if (maxLatencyMs > minLatencyMs) {
            latency = minLatencyMs + (long) (random.nextDouble() * (maxLatencyMs - minLatencyMs));
        }

        log.info("Simulating external payment processing for paymentId={}, latency={}ms", paymentId, latency);
        try {
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Payment simulation interrupted for paymentId={}", paymentId);
        }

        double roll = random.nextDouble();
        boolean succeeded = roll >= failureRate;
        log.info("Simulation result for paymentId={}: outcome={} (roll={}, threshold={})",
                paymentId, succeeded ? "SUCCEEDED" : "FAILED", roll, failureRate);
        return succeeded;
    }

    public long getMinLatencyMs() {
        return minLatencyMs;
    }

    public long getMaxLatencyMs() {
        return maxLatencyMs;
    }

    public double getFailureRate() {
        return failureRate;
    }
}
