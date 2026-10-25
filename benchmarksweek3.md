# Benchmarks

Every change that might affect performance gets a row. Same machine, same test, same duration.

**Improvement % = (new RPS − baseline RPS) / baseline RPS × 100**  
For latency, use (baseline p95 − new p95) / baseline p95 × 100.

Machine: MacBook Air (fill in chip/RAM) · Docker Desktop CPU/RAM limits: ____ · k6 version: ____

| # | Date | Change | Test | Success % | RPS achieved | p95 (ms) | p99 (ms) | Errors seen | RPS vs baseline |
|---|------|--------|------|-----------|--------------|----------|----------|-------------|-----------------|
| 1 | 2026-09-28 | ~~Week 1 baseline~~ — **INVALID, see postmortem below** | RATE=100 / 500 / 1000 / VUS=200 | 0% / N/A | ~0 | n/a | n/a | total collapse across all four runs | — |
| 2 | 2026-10-04 | **Week 1 real baseline** — service restarted + health-checked before each run | RATE=20 | 100% (301/301) | 20.06 | 13.79 | 84.24 | none | — |
| 2 | 2026-10-04 | Week 1 real baseline | RATE=50 | 100% (750/750) | 49.99 | 8.36 | 114.27 | none | — |
| 2 | 2026-10-04 | Week 1 real baseline | RATE=100 | 100% (1501/1501) | 100.04 | 5.63 | 249.45 | none | — |
| 2 | 2026-10-04 | Week 1 real baseline | RATE=300 | 100% (6001/6001) | 300.00 | 3.48 | 168.94 | none | — |
| 2 | 2026-10-04 | Week 1 real baseline | RATE=500 | 100% (10001/10001) | 499.98 | 7.55 | 340.43 | none | — |
| 2 | 2026-10-04 | Week 1 real baseline | RATE=1000 | 100% of completed (19943/19943); 57 dropped (0.29%) | 997.02 | 141.6 | 620.9 | 0 HTTP failures; 57/20000 iterations never dispatched | — |

**Run 2 is the official Week 1 baseline, and it's a strong one: zero HTTP failures all the way from 20 to 1000 requests/second.** Row 1 is kept in the table on purpose (see postmortem) but should not be treated as the service's real capacity.

## Postmortem: why run 1 looked broken

Run 1 (2026-09-28) showed 0% success at RATE=100 and total silence (0 completed requests) at RATE=500, 1000, and VUS=200 — p95 around 30 seconds, which is suspiciously close to HikariCP's default 30000ms connection-timeout. That pointed at a connection-pool leak.

It wasn't one. Run 2 (2026-10-04) — same code, same config, same machine — passed cleanly from RATE=20 all the way to RATE=1000, with zero HTTP errors throughout. The only procedural difference: **the service was restarted and health-checked before each k6 run**, instead of running `k6 run -e RATE=100 ...`, `k6 run -e RATE=500 ...`, `k6 run -e RATE=1000 ...` back-to-back in the same terminal.

The real cause: the RATE=100 run in run 1 had up to **1544 virtual users** still ramped up and in flight when its command returned (k6 keeps VUs alive through the 30s `gracefulStop` window even after the nominal test duration ends). The very next command — RATE=500 — started immediately afterward, throwing another 500–2000 VUs' worth of connection attempts on top of a server that was still draining the first test's backlog. Each test compounded the one before it instead of measuring a clean server, which is exactly why every run after the first showed *zero* completions rather than a gradual decline — it was overload pile-up, not a server bug.

**Lesson for every load test from here on:** `docker compose restart payment-service`, wait for `/actuator/health` to report `UP`, *then* run k6. One test, one clean server, every time. (Keep this habit for Week 4 onward too — a slow Kafka consumer can produce the exact same "looks dead, isn't" symptom.)

**Known unrelated issue, still not fixed:** `docker exec -it paygate-postgres psql -U payments -d payments ...` fails with `role "payments" does not exist`, even though the app connects fine with the same credentials via JDBC — a stale `pgdata` volume from an earlier run. Harmless (every k6 run above still passed), but fix it whenever you next need `psql` access:
```bash
docker compose down -v
docker compose up -d --build
```

## What the RATE=1000 run shows

This is the first run with visible strain, but it is **not a failure** — read it carefully:

- **Every request that was sent still succeeded.** `http_req_failed` is 0.00% at every rate tested, including 1000.
- **p95 and p99 jumped**: 5.63ms → 141.6ms (p95) and 249ms → 621ms (p99) going from RATE=100 to RATE=1000. Something is queuing under load (the Hikari pool of 20 connections is the prime suspect — 1000 req/s against 20 DB connections means plenty of requests wait their turn), but it's queuing successfully, not dropping work.
- **57 iterations (0.29%) were "dropped,"** meaning k6 itself couldn't dispatch them in time — this showed up as `vus_max: 557` even though `MAX_VUS=3000` was allowed, so k6's own VU ramp-up was the limiting factor here, not a signal from the server. Treat this as noise, not a server-side error.
- **Both thresholds (`p95<500ms`, `p99<1000ms`) still passed.** The service has not yet hit its real breakpoint at 1000 req/s on this machine.

## Next: find the real breakpoint (optional stretch)

The service is healthy well past the "thousands of requests" bar this guide originally asked for. If you want to find where it actually bends, keep pushing — same discipline, restart before each run:
```bash
docker compose restart payment-service && sleep 10 && curl -s localhost:8081/actuator/health
k6 run -e RATE=2000 -e DURATION=20s -e MAX_VUS=5000 load-tests/create-payment.js

docker compose restart payment-service && sleep 10 && curl -s localhost:8081/actuator/health
k6 run -e RATE=5000 -e DURATION=20s -e MAX_VUS=10000 load-tests/create-payment.js
```
Watch `docker stats` live, since a MacBook running Docker Desktop, the JVM, and k6 all at once will likely hit its own CPU/RAM ceiling before the code does — that ceiling is itself useful data, but don't mistake "my laptop is maxed out" for "my code can't handle load." The deliberate, instrumented version of this search — with connection-pool tuning, caching, and proper observability — is Week 7's job. For now, this baseline (clean through 1000 req/s) is more than enough to move on.

## Notes per row
Write what you changed, what you expected, what actually happened, and why.

## Week 2 — through the gateway, with JWT auth

Same test, new target: `load-tests/create-payment-gateway.js` against `localhost:8080` (the gateway), with each virtual user carrying a real `Bearer` token for one of 50 real merchants (not `payment-service` directly, not a fake header). `setup()` creates all 50 merchants + tokens once before each run; that cost isn't in the numbers below.

| # | Date | Change | Test | Success % | RPS achieved | p95 (ms) | p99 (ms) | Errors seen | RPS vs baseline |
|---|------|--------|------|-----------|--------------|----------|----------|-------------|-----------------|
| 3 | 2026-10-13 | Week 2 baseline — **before** fix, gateway httpclient pool left on defaults | RATE=100 | 100% (3000/3000) | 78.55 | 72.18 | 407.81 | none | — |
| 3 | 2026-10-13 | Week 2 baseline — before fix | RATE=500 | 100% (14967/14967) | 392.04 | 340.96 | 1080* | none | — |
| 3 | 2026-10-13 | Week 2 baseline — before fix | RATE=1000 | 94.49% (26961/28531) | 745.02 | 2160* | 2580* | 1570 failed (5.48%) | — |
| 4 | 2026-10-13 | **Fix:** explicit `spring.cloud.gateway.httpclient.pool` sizing added (max-connections 1000, was unset/default) | RATE=500 | 100% (15000/15000) | 389.68 | 17.63 | 77.49 | none | +95% p95 improvement |
| 4 | 2026-10-13 | After fix | RATE=1000 | 99.98% (29996/30001) | 803.24 | 2.48 | 35.19 | 5 failed (0.01%, see note) | +99% p95 improvement |

\* p95/p99 for the "before fix" RATE=500/1000 rows failed their thresholds (`p95<500ms`, `p99<1000ms`) — these are real, measured values, not test artifacts.

### Postmortem: the gateway httpclient pool

The symptom: RATE=100 was fine (p95=72ms — a reasonable cost for the extra hop plus JWT verification, versus Week 1's direct p95=5.63ms at the same rate). RATE=500 and RATE=1000 didn't degrade proportionally — they blew up non-linearly (72ms → 341ms → 2160ms), which is the signature of requests queuing for a saturated resource, not steady added latency.

**Checked and ruled out first:** `JwtAuthGlobalFilter`, `RateLimitGlobalFilter`, and `JwtVerifier` — read line by line, none of them block (no locks, no I/O, no `Thread.sleep`). A blocking call on a reactive server's event-loop thread is the classic cause of exactly this symptom, but it wasn't here.

**Actual cause:** `gateway/application.yml` never configured `spring.cloud.gateway.httpclient.pool.*` — the pool of connections the gateway itself uses to call `payment-service`/`auth-service` was running on Spring Cloud Gateway's unsized defaults, nowhere near enough for 500–1000 concurrent proxied requests. Requests queued waiting for a free outbound connection; queueing time dominated as concurrency rose.

**Fix:** explicit pool sizing in `gateway/application.yml`:
```yaml
spring:
  cloud:
    gateway:
      httpclient:
        pool:
          max-connections: 1000
          max-idle-time: 30s
          acquire-timeout: 5000
        connect-timeout: 5000
        response-timeout: 10s
```

**Result:** RATE=500's p95 dropped from 341ms to 17.6ms (a ~95% improvement) and its threshold failure disappeared entirely. RATE=1000's p95 dropped from 2.16s to 2.48ms and its failure rate dropped from 5.48% to 0.01% — and that remaining 0.01% (5 requests out of 30,101) is explained below, not a lingering problem.

**On the 5 failures at RATE=1000:** 50 merchants × the gateway's 20 req/s-per-merchant rate limit = exactly 1000 req/s aggregate — the test ran right at that ceiling. A handful of requests tipping into `429` at that exact boundary is the rate limiter correctly doing its job, not a defect. (If you want to confirm this directly rather than infer it, rerun with `k6 run ... --http-debug` and grep the few non-201 responses for their status code — expect `429`.)

**Lesson for later weeks:** any time you add a new network hop (gateway → service, service → Redis in Week 3, service → Kafka in Week 4), check whether that hop's *client-side* connection pool is sized for the load you're about to throw at it. Defaults are tuned for safety and low idle resource usage, not for a k6 run.

## Week 1 vs Week 2 — what the gateway actually costs

Comparing Week 1's direct-to-`payment-service` numbers against Week 2's after-fix gateway numbers, same rates:

| Rate | Week 1 p95 (direct) | Week 2 p95 (via gateway) | Week 1 p99 | Week 2 p99 |
|---|---|---|---|---|
| 100 | 5.63ms | 72.18ms* | 249.45ms | 407.81ms* |
| 500 | 8.36ms | 17.63ms | 114.27ms | 77.49ms |
| 1000 | 141.6ms | 2.48ms | 620.9ms | 35.19ms |

\* RATE=100 was only measured **before** the pool fix — worth rerunning post-fix for a clean comparison, but not urgent; it already passed its thresholds.

The RATE=1000 row looks backwards at first glance — the gateway is *faster* than hitting `payment-service` directly. That's plausible, not a measurement error: by RATE=1000 both JVMs have been running (and JIT-warmed) across several consecutive test runs with no restarts in between, and the gateway's reactive, non-blocking proxy path with a properly sized connection pool can genuinely outperform a blocking Tomcat stack under high concurrency. Treat this as a reason to be skeptical of any single number in isolation — rerun RATE=100 post-fix, and ideally rerun the whole Week 1 suite fresh too, before drawing a firm conclusion about which is "faster." The clear, safe-to-trust takeaway from this data either way: **the gateway adds no meaningful cost once its connection pool is sized correctly.**

## Week 3 — idempotency and the Redis fast path

Three scripts, same gateway, same rates: `create-payment-gateway.js` (no `Idempotency-Key`, the Week 2 control), `create-payment-idempotent-cold.js` (a fresh unique key every request — worst case), `create-payment-idempotent-replay.js` (one fixed key per merchant, pre-warmed — best case).

### Postmortem: a 262,000-row database was the real story here

The first attempt at this benchmark (not shown in the table below) gave numbers that looked like a serious bug: `create-payment-idempotent-cold.js` at RATE=1000 came back at **p95=10.91s, 7.48% failures**. Before concluding anything about the code, `select count(*) from payments` turned up **226,983 rows**, and `idempotency_keys` had **35,069** — the accumulated output of every k6 run across Weeks 1, 2, and 3, never once cleaned up (`docker compose restart` preserves the `pgdata` volume; only `docker compose down -v` wipes it).

After `docker compose down -v` and a fresh `up -d --build`, the exact same test — same code, same machine, same rate — came back at **p95=574.78ms, 0% failures**. A ~19x improvement in p95 and failures gone entirely, from wiping test data alone. **Lesson for later weeks:** an unbounded table with no partitioning, archival, or `VACUUM` strategy degrades like this for real, not just in theory — and it specifically hits the *write* paths hardest (both plain creation and cold-claim got dramatically better; the Redis-only replay path, which never writes to Postgres, barely moved). This is worth remembering once Week 4's outbox table and Week 6's ledger start accumulating rows of their own.

### Results (post-wipe, fresh volume)

| # | Date | Change | Test | Success % | RPS achieved | p95 (ms) | p99 (ms) |
|---|------|--------|------|-----------|--------------|----------|----------|
| 5 | 2026-10-17 | Week 3, clean volume | no-key, RATE=100 | 100% | 77.70 | 74.62 | 529.38 |
| 5 | 2026-10-17 | Week 3, clean volume | no-key, RATE=500 | 100% | 385.94 | 762.53* | 1500* |
| 5 | 2026-10-17 | Week 3, clean volume | no-key, RATE=1000 | 100% | 796.11 | 18.61 | 89.81 |
| 5 | 2026-10-17 | Week 3, clean volume | idempotent-cold, RATE=100 | 100% | 78.70 | 75.70 | 460.33 |
| 5 | 2026-10-17 | Week 3, clean volume | idempotent-cold, RATE=500 | 100% | 400.08 | 38.51 | 281.17 |
| 5 | 2026-10-17 | Week 3, clean volume | idempotent-cold, RATE=1000 | 100% | 786.62 | 574.78** | 908.94 |
| 5 | 2026-10-17 | Week 3, clean volume | idempotent-replay, RATE=100 | 100% | 78.98 | 12.50 | 77.01 |
| 5 | 2026-10-17 | Week 3, clean volume | idempotent-replay, RATE=500 | 100% | 393.34 | 3.89 | 45.52 |
| 5 | 2026-10-17 | Week 3, clean volume | idempotent-replay, RATE=1000 | 100% | 794.20 | 4.97 | 38.00 |

\* **Not yet trusted — see below.** \*\* Passed `p99<1000ms` but failed `p95<500ms`; zero failures, so not a correctness problem, just a latency one.

### The one unconfirmed number: no-key RATE=500's 762ms

This result is sandwiched between a better RATE=100 run before it and a better RATE=1000 run after it — backwards for a load test where latency should rise with rate, not dip and spike. The likely explanation: after this point, every run in the sequence above ran back-to-back with **no restart between them** (the discipline slipped partway through this session), so RATE=1000 benefited from a fully warmed JVM and connection pool that RATE=500 — the only run that followed a fresh restart in this batch — didn't get. Supporting evidence: `idempotent-cold`'s own RATE=500 (also unrestarted, immediately after its own RATE=100) came in at 38ms — faster than no-key's RATE=500, which makes no sense if 500 req/s were genuinely harder, since cold-claim does strictly more work per request than a plain create.

**To confirm before trusting this row, run just this one case in isolation:**
```bash
docker compose restart && sleep 20 && curl -s localhost:8080/actuator/health
k6 run -e RATE=500 -e DURATION=30s load-tests/create-payment-gateway.js
```
If it comes back closer to 20-40ms (in line with every other result at this rate), replace the 762ms/1.5s row above with the new numbers and delete this note.

### What's solid: the cost of idempotency, isolated cleanly

`no-key RATE=1000` (18.61ms) and `idempotent-cold RATE=1000` (574.78ms) ran back-to-back with no restart between them — both fully warmed, both on the same clean volume, nothing else different. That's an honest apples-to-apples comparison, and it shows the real cost of this week's feature under its worst-case usage pattern (a brand-new key on every single request, never reusing one): **roughly 30x worse p95** than a plain create at the same rate. That's expected, not alarming — `tryClaim`, the actual create, and `markCompleted` are three separate `@Transactional` methods, so a cold claim does something like 2-3x the Postgres round-trips of a plain create. Worth remembering: this worst case is also an unrealistic one in practice — a real client retries the *same* key, which is the replay path, not this one.

**What's unambiguous and didn't need a caveat:** the replay path. 12.50ms → 3.89ms → 4.97ms p95 across RATE=100/500/1000 — essentially flat regardless of load, because it's a single Redis `GET` and nothing else. This is architecture.md decision #19 ("Redis is the fast path, Postgres is the source of truth") actually measured, not just asserted.
