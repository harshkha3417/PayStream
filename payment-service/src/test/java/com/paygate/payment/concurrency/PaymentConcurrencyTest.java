package com.paygate.payment.concurrency;

import com.paygate.payment.Dto.CreatePaymentRequest;
import com.paygate.payment.Entity.Payment;
import com.paygate.payment.Enum.PaymentMethod;
import com.paygate.payment.Enum.PaymentStatus;
import com.paygate.payment.Exception.InvalidStatusTransitionException;
import com.paygate.payment.outbox.OutboxEventRepository;
import com.paygate.payment.repository.IdempotencyRecordRepository;
import com.paygate.payment.repository.PaymentRepository;
import com.paygate.payment.repository.PaymentStatusHistoryRepository;
import com.paygate.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deliberately named *Test, not *IT: this project only runs `mvn test`
 * (Surefire), never `mvn verify` (Failsafe) — an *IT-suffixed class would be
 * silently skipped and you'd never know these ran. If this test suite grows
 * large enough that slow, container-backed tests should be separated from
 * fast unit tests, that's the point to introduce the Failsafe plugin and
 * rename this to an *IT class on purpose.
 *
 * Runs against a REAL Postgres (via Testcontainers), not H2 — the two things
 * this class proves (the idempotency claim race, the optimistic-lock race)
 * are both Postgres-specific behaviors (a real unique-constraint conflict, a
 * real UPDATE ... WHERE version = ? affecting zero rows) that an in-memory
 * database would not reliably reproduce the same way.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class PaymentConcurrencyTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("payments_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void overrideDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // No Redis container here on purpose: IdempotencyService degrades to
        // "always miss the cache, always ask Postgres" if Redis is
        // unreachable, which is exactly the fallback behavior this test
        // wants to exercise — correctness must not depend on Redis being up.
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "1"); // deliberately unreachable
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("outbox.poll-interval-ms", () -> "999999999");
    }

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    PaymentService paymentService;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    PaymentStatusHistoryRepository historyRepository;

    @Autowired
    IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void cleanDb() {
        outboxEventRepository.deleteAll();
        historyRepository.deleteAll();
        paymentRepository.deleteAll();
        idempotencyRecordRepository.deleteAll();
    }

    /**
     * Fires the same Idempotency-Key + same body from several threads at
     * once. The ONE guarantee that must always hold, no matter how the race
     * resolves: exactly one payment row ever gets created.
     *
     * What's deliberately NOT asserted: that every response is identical.
     * Depending on timing, a thread can either (a) win the claim and get a
     * fresh 201, (b) arrive after the winner finished and get a replayed
     * 201 with the exact same body, or (c) arrive while the winner is still
     * mid-flight and get a 409 (see IdempotencyService's class comment for
     * why that's 409-and-retry rather than blocking). All three are correct.
     * A test that demanded identical responses from every thread would be
     * wrong about what this feature actually guarantees.
     */
    @Test
    void concurrentRequestsWithSameIdempotencyKeyNeverCreateMoreThanOnePayment() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String merchantId = "merchant_concurrency_" + UUID.randomUUID();
        String body = """
                {"amount":49900,"currency":"INR","method":"UPI","description":"concurrency test"}""";

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);

        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("X-Merchant-Id", merchantId);
                headers.set("Idempotency-Key", idempotencyKey);
                HttpEntity<String> request = new HttpEntity<>(body, headers);

                ready.countDown();
                go.await(); // every thread blocks here until all are ready, then releases together
                return restTemplate.postForEntity("/api/v1/payments", request, String.class);
            }));
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown(); // release all threads at once to maximize actual overlap
        pool.shutdown();

        List<ResponseEntity<String>> responses = new ArrayList<>();
        for (Future<ResponseEntity<String>> f : futures) {
            responses.add(f.get(10, TimeUnit.SECONDS));
        }

        // The real guarantee: no matter how the race resolved, Postgres's
        // ON CONFLICT DO NOTHING let exactly one request through to actually
        // create a payment.
        assertThat(paymentRepository.count()).isEqualTo(1);

        List<ResponseEntity<String>> created = responses.stream()
                .filter(r -> r.getStatusCode().value() == 201)
                .collect(Collectors.toList());
        List<ResponseEntity<String>> inProgress = responses.stream()
                .filter(r -> r.getStatusCode().value() == 409)
                .collect(Collectors.toList());

        assertThat(created.size() + inProgress.size())
                .as("every response should be either a 201 (won/replayed) or a 409 (in progress)")
                .isEqualTo(threads);
        assertThat(created).as("at least one request has to win the race").isNotEmpty();

        Set<String> distinctCreatedBodies = created.stream()
                .map(ResponseEntity::getBody)
                .collect(Collectors.toSet());
        assertThat(distinctCreatedBodies)
                .as("the winner's 201 and every replay of it must be byte-identical")
                .hasSize(1);
    }

    /**
     * Two requests racing to reuse the same key with DIFFERENT bodies: the
     * second one must be rejected (422), never silently accepted or allowed
     * to create a second payment.
     */
    @Test
    void sameIdempotencyKeyWithDifferentBodyIsRejected() {
        String idempotencyKey = UUID.randomUUID().toString();
        String merchantId = "merchant_reuse_" + UUID.randomUUID();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Merchant-Id", merchantId);
        headers.set("Idempotency-Key", idempotencyKey);

        ResponseEntity<String> first = restTemplate.postForEntity("/api/v1/payments",
                new HttpEntity<>("""
                        {"amount":100,"currency":"INR","method":"UPI"}""", headers),
                String.class);
        assertThat(first.getStatusCode().value()).isEqualTo(201);

        ResponseEntity<String> second = restTemplate.postForEntity("/api/v1/payments",
                new HttpEntity<>("""
                        {"amount":999,"currency":"INR","method":"UPI"}""", headers),
                String.class);
        assertThat(second.getStatusCode().value()).isEqualTo(422);

        assertThat(paymentRepository.count()).isEqualTo(1);
    }

    /**
     * Two threads racing to apply the exact same legal status transition to
     * the exact same payment. Exactly one must win; the other must lose —
     * via whichever error Hibernate's optimistic lock produces for the
     * interleaving that actually happened:
     *   - both threads read the row before either writes: the loser's
     *     UPDATE ... WHERE version = ? affects zero rows ->
     *     ObjectOptimisticLockingFailureException.
     *   - the winner fully commits before the loser even reads: the loser
     *     reads a payment that's already PROCESSING and tries to transition
     *     PROCESSING -> PROCESSING, which the state machine itself forbids ->
     *     InvalidStatusTransitionException.
     * Both are legitimate "you lost the race" outcomes for this test — which
     * one you get is a timing accident, not a behavior either way is
     * supposed to guarantee.
     */
    @Test
    void concurrentStatusTransitionsOnSamePaymentOnlyOneSucceeds() throws Exception {
        Payment payment = paymentService.create("merchant_lock_test_" + UUID.randomUUID(),
                new CreatePaymentRequest(10000L, "INR", PaymentMethod.UPI, "lock test"));

        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);

        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    paymentService.transition(payment.getId(), PaymentStatus.PROCESSING);
                    return true;
                } catch (ObjectOptimisticLockingFailureException | InvalidStatusTransitionException e) {
                    return false;
                }
            }));
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        pool.shutdown();

        long succeeded = 0;
        for (Future<Boolean> f : futures) {
            if (f.get(10, TimeUnit.SECONDS)) succeeded++;
        }

        assertThat(succeeded).as("exactly one thread should win the transition race").isEqualTo(1);

        Payment reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
    }
}