# Phase 6 — Implementation Plan (Milestones)

Four milestones, each with a definition-of-done. The plan stages risk: end-to-end happy path first, then concurrency, then crash recovery, then chaos + docs. Every milestone produces a green test suite before moving on.

## Day 1 — Happy path

**Scope.** Spring Boot app stands up. Postgres via docker-compose. Flyway runs V1. `POST /charges` accepts the header, persists an idempotency row, makes the (fake) PSP call, returns 201. Same key + same body → cached response. Same key + different body → 422.

**Build artifacts.**

- `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`.
- `docker-compose.yml` (Postgres 15).
- `application.yml`, `application-test.yml`.
- `IdempotencyApplication.java`, `ChargesController.java`, `IdempotencyInterceptor.java`, `IdempotencyService.java` (single-phase MVP), `ChargeService.java`, `ExternalPaymentClient.java` (stub returning `ch_<uuid>`), `IdempotencyKey.java`, `Ride.java`, `AuditLog.java`, `User.java`, repositories, `RequestHash.java` (canonicalization + SHA-256).
- `V1__schema.sql` (done in Phase 5).

**Definition of done.**

- `./gradlew test` runs three integration tests green:
  - `testHappyPath`
  - `testDuplicateKeyReturnsCachedResponse`
  - `testDuplicateKeyDifferentBodyRejects`
- Tests use Testcontainers Postgres.

## Day 2 — Concurrency

**Scope.** Implement the `SELECT … FOR UPDATE` + `locked_at` two-layer mutex. Replace the single-phase MVP with the dispatcher loop that re-reads `recovery_point` between phases. Return 409 on fresh lock; reclaim on stale lock.

**Build artifacts.**

- Refine `IdempotencyService.acquireOrServe()` and `IdempotencyService.execute()` loop.
- Add `idempotency_request_in_progress` error code and `Retry-After-Ms` header on 409.
- Add `lock.staleness` config (default 90s).

**Definition of done.**

- `testConcurrentRequestsExecuteOnce` passes: 100 threads, same key, exactly **1** ride row and exactly **1** PSP call observed; all 100 threads receive 201 with the same body (some via direct execution, others via the cache after backing off from 409).
- Lock-stale reclaim is exercised by a unit test that mutates `locked_at` to 2 minutes ago and confirms resumption.

## Day 3 — State machine & recovery

**Scope.** Break the work into the four recovery points. Each phase wraps in `@Transactional(propagation = REQUIRES_NEW)`. Recovery-point UPDATE commits *with* the phase's local mutations. Derive the downstream PSP idempotency key from the inbound row id. Implement the state-machine dispatch loop.

**Build artifacts.**

- `IdempotencyService.runStartedPhase()`, `runExternalCallPhase()`, `runFinalizePhase()` with `@Transactional(propagation = REQUIRES_NEW)`.
- `ExternalPaymentClient.charge(amount, currency, customerId, derivedIdempotencyKey)` — derivedIdempotencyKey is a required parameter.
- Audit log writes in every phase.
- Staged-jobs INSERT in finalize, idempotent via `ON CONFLICT (idempotency_key_id, job_name) DO NOTHING`.

**Definition of done.**

- `testCrashAfterDbCommitBeforeResponseRecoversCorrectly` — throws at the boundary of `tx3→tx4`, retry resumes and returns the same response.
- `testCrashDuringExternalApiCallNoDoubleCharge` — kill the JVM-side handler after the PSP returns 200 but before `tx3` commits; retry observes 1 PSP call's idempotency-cache hit and 1 charge row. Assert that `ExternalPaymentClient` saw the same `derivedKey` on both attempts.

## Day 4 — Failure injection + docs

**Scope.** Wire Toxiproxy in front of the fake PSP, inject failures at each transition, prove recovery. Implement the reaper. Add JMH benchmarks. Write the README and the blog post.

**Build artifacts.**

- `FailureInjectionTest.java` — chaos tests for F2–F8 (F1 is the trivial network-pre-call case).
- `Reaper.java` (@Scheduled hourly), `JobDrain.java` (@Scheduled 5s).
- `IdempotencyBenchmark.java` (JMH).
- `README.md` (10-section structure from Phase 10 spec).
- `BLOG.md` (1500–2500 words).
- Toxiproxy sidecar in `docker-compose.yml`.

**Definition of done.**

- All 8 named tests from Phase 8 spec pass.
- JMH benchmarks compile and run; numbers populated in README only from actual local runs.
- Reaper test confirms expired rows are deleted and that `rides.idempotency_key_id` becomes NULL (not cascading).
- Blog post checked into repo.

---

**Sources:**

- [Implementing Stripe-like Idempotency Keys in Postgres — brandur.org](https://brandur.org/idempotency-keys)
- [Designing robust and predictable APIs with idempotency — Stripe](https://stripe.com/blog/idempotency)
