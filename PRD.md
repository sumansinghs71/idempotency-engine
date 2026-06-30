# Phase 1 — Product Requirements Document

**Project:** IdemEngine — a Stripe-style idempotency middleware for payment-class APIs
**Owner:** Platform / Payments
**Status:** Draft v1
**Last updated:** 2026-05-28

## 1. Problem statement

Our merchants integrate with our payment API over the public internet. Networks fail (timeouts, connection resets, half-closed sockets, mobile-radio drops, intermittent ISP outages), our servers fail (deploys, OOMs, host restarts), and the PSPs we sit in front of also fail. None of these failures is rare in absolute terms at the request volumes we run.

When a `POST /charges` request fails ambiguously, the merchant's client has no way to know whether the charge went through. The naive options are both bad: assume failure and retry — and risk double-charging the customer; or assume success and not retry — and risk silently losing the customer's money and the merchant's order. Either way, the merchant has to write reconciliation code, and we own a class of support tickets that read "you charged my customer twice."

The contract we want is the one Stripe established in 2017: the merchant attaches an `Idempotency-Key` header to the request, retries on any ambiguous failure, and the server guarantees the customer is charged at most once and the merchant receives the identical response on every retry.

## 2. Goals & non-goals

### Goals

- **G1.** Exactly-once charge semantics for `POST /charges` under any combination of client retry and server-side failure.
- **G2.** Cached, byte-identical replay of completed responses for the same idempotency key + body.
- **G3.** Concurrency safety: two simultaneous retries of the same key cannot both execute the side effects.
- **G4.** Per-user key scoping: an idempotency key leaked from one merchant is useless to another.
- **G5.** Bounded storage growth via TTL-based reaping of completed keys.
- **G6.** Observable: every state transition emits an audit row and a metric.

### Non-goals (explicit)

- **NG1.** Distributed multi-region active-active consensus. We run in a single primary region with a hot standby. Multi-region exactly-once is future work and requires either a different storage primitive (Spanner-class) or careful Raft on top of Postgres — both out of scope.
- **NG2.** Generalized idempotency for arbitrary endpoints. The first cut targets `POST /charges`; the middleware is built to extend, but acceptance criteria are on charges only.
- **NG3.** Cross-PSP idempotency. The derived downstream key is for our single PSP. Multi-PSP routing layered on top is future work.
- **NG4.** Client-side SDK. We expose an HTTP contract; merchants supply their own retry loop (Stripe's `stripe-node` shows what a good one looks like).

## 3. Personas & use cases

### P1 — Merchant integration engineer

Integrates our API into their checkout. Their code calls `POST /charges` with an `Idempotency-Key` header (UUIDv4). They retry on 5xx, 409, and connection errors with exponential backoff + jitter, exactly as Stripe's SDK does. Their success criterion is: "if my retry loop terminates, the customer is in a defined state (charged once + response received, or not charged + error)."

### P2 — End customer (the cardholder)

Never interacts with our API directly. Their success criterion is: their card is charged exactly once for any one checkout attempt.

### P3 — On-call engineer at our company

Gets paged when our duplicate-charge rate is non-zero or when a chaos test detects a recovery failure. Uses the `audit_logs` table and the metrics emitted by the state machine to diagnose. Their success criterion: every state transition is reconstructable post-hoc from the audit table.

### Primary use cases

- **UC1.** New checkout: merchant generates a fresh UUIDv4, calls `POST /charges`, gets `201`, customer charged once.
- **UC2.** Retry after network blip: merchant calls again with the same key after a timeout; gets the same `201` and the same body; no second charge.
- **UC3.** Retry after server crash mid-call: merchant retries; server resumes from the recovery point, downstream PSP call is deduped by the derived key, response is committed and returned.
- **UC4.** Two parallel retries (over-eager client or load-balancer dupes): one wins, executes once, returns; the other gets `409` and retries on backoff to get the cached response.
- **UC5.** Client bug (same key, different body): server returns `422` with a clear error code; merchant fixes their code.

## 4. Functional requirements (numbered, testable)

| ID | Requirement | Test that proves it |
|---|---|---|
| FR-1 | `POST /charges` accepts an `Idempotency-Key` HTTP header. Missing header → `400 Bad Request` with code `idempotency_key_required`. | `testMissingKeyRejects` |
| FR-2 | Same `(user_id, key)` + same request body + previous request `finished` → return the cached `response_code` and `response_body` byte-identical. No re-execution of side effects. | `testDuplicateKeyReturnsCachedResponse` |
| FR-3 | Same `(user_id, key)` + *different* request body → `422 Unprocessable Entity` with code `idempotency_key_body_mismatch`. | `testDuplicateKeyDifferentBodyRejects` |
| FR-4 | Two concurrent requests with the same `(user_id, key)`: exactly one executes the side effects; the other receives `409 Conflict` with code `idempotency_request_in_progress` (and after backoff, eventually the cached response). Race window covered by the row lock + `locked_at` staleness check. | `testConcurrentRequestsExecuteOnce` |
| FR-5 | Server crash at any of the 8 enumerated failure points (RESEARCH.md §5) followed by a client retry must converge to: customer charged exactly once, response stored, identical body returned. | `testCrashAfterDbCommit*`, `testCrashDuringExternalApiCall*` |
| FR-6 | Idempotency keys expire after a configurable TTL (default 24h). A request with an expired key is treated as a *new* request: server creates a fresh row (the expired one is purged by the reaper). | `testExpiredKeyAllowsNewRequest` |
| FR-7 | Keys are scoped per user. The same key string from two different `user_id`s does not collide. Uniqueness constraint is `(user_id, key)`. | `testKeyCollisionAcrossUsersAllowed` |

## 5. Non-functional requirements

### NFR-1 — Latency

- **Cached-response path** (key already `finished`, body matches): p99 ≤ 10 ms server-side. One indexed point read + one HTTP write.
- **Cold path** (new key, happy path): p99 server-side latency dominated by the downstream PSP call; our overhead target is ≤ 15 ms over the PSP baseline.

### NFR-2 — Throughput

- 5,000 sustained `POST /charges`/sec/instance with mixed cache-hit/miss ratios representative of production (~30% retry rate from observed client behavior).
- Locking must not be a contention bottleneck at 100 concurrent requests against the same key — the lock holder runs; everyone else gets `409` fast.

### NFR-3 — Storage growth

- ~5 KB per key row average (JSONB body + JSONB response). At 5,000 req/s sustained, that's ~2.1 GB/day. With a 24h TTL and a reaper running hourly, steady state is bounded.
- The reaper must keep up: deletion rate ≥ insertion rate at steady state.

### NFR-4 — Observability

- Every state transition writes one row to `audit_logs` (idempotent on `(idempotency_key_id, from_state, to_state, attempt_no)`).
- Metrics emitted (Micrometer): `idem.requests.total{state}`, `idem.cache_hits.total`, `idem.lock_wait.duration`, `idem.recovery.total{from_state}`, `idem.body_mismatch.total`, `idem.external_call.duration`.
- Structured logs include `idempotency_key`, `user_id`, `recovery_point`, `attempt_no`.

### NFR-5 — Security

- Idempotency keys are sensitive: they let a holder replay a state-changing API call. We scope `(user_id, key)`, so a leaked key is replayable only by its owner.
- Keys are bounded at 100 chars to prevent log/DB blowup.
- Request body fingerprint is **SHA-256 of the canonicalized body**, not the body itself, for cache comparison. (We still store the body in `request_body` for debugging / the completer process, with normal PII redaction at the logging layer.)
- TLS termination upstream; keys never appear in URLs or query strings.

### NFR-6 — Availability

- 99.95% on the `POST /charges` endpoint.
- Postgres failover ≤ 30s; during failover, requests `409` with `Retry-After`, the merchant SDK retries.

### NFR-7 — Idempotent under partial-deploy

- A blue/green deploy where two app versions run simultaneously must not break the state machine. State machine phase names are append-only enums; older code that encounters a phase name it doesn't recognize must hard-fail with a clear error, not silently skip.

## 6. Success metrics

- **Primary:** duplicate-charge rate measured by reconciling our `idempotency_keys.finished` count against PSP charge IDs per `(user_id, key)`. Target: **zero** non-test duplicate charges/month.
- **Cached-response p99 latency:** < 10ms server-side.
- **Recovery success rate under chaos tests:** 100% across all 8 named failure points.
- **Reaper backlog:** count of `expired_at < now() - 1h` rows ≤ 1% of total rows at any time.
- **Concurrency safety:** the `testConcurrentRequestsExecuteOnce` test passes with 100 threads and asserts exactly 1 PSP call and exactly 1 ride row.

## 7. Out of scope / future work

- Multi-region active-active. Requires either cross-region serializable transactions or a CRDT-friendly idempotency-key scheme (current consensus: not feasible without sacrificing one of the guarantees).
- A "completer" daemon (Brandur's term) that pushes abandoned `started`/`customer_validated` keys to completion when the client never retries. Sketched in DESIGN.md but not in the v1 implementation.
- Per-endpoint TTL overrides.
- A pluggable storage adapter (Redis, DynamoDB). Postgres is the v1; the service is structured to swap by replacing `IdempotencyKeyRepository` and the locking SQL.
- A merchant-facing dashboard for inspecting idempotency-key history.

## 8. Open questions (resolved per "Stripe-canonical" rule)

- **Q: Should we hash the body and compare hashes, or compare canonical JSON byte-for-byte?**
  Stripe canonical: hash. SHA-256 of a canonicalized body. Assumption: canonicalization sorts JSON keys and strips insignificant whitespace. Continuing.
- **Q: Should `locked_at` be cleared on every successful response, or only `finished` transition?**
  Stripe canonical / Brandur: clear it on `Response` (i.e. when the request terminates either successfully or via a non-recoverable error). Continuing.
- **Q: TTL default?**
  Brandur recommends 72h to survive a Friday-night bug. Stripe's documented default is 24h. We pick 24h to match Stripe's public contract; configurable.

---

**Sources:**

- [Designing robust and predictable APIs with idempotency — Stripe](https://stripe.com/blog/idempotency)
- [Implementing Stripe-like Idempotency Keys in Postgres — brandur.org](https://brandur.org/idempotency-keys)
- [Idempotency and Retry Logic — stripe/stripe-node, DeepWiki](https://deepwiki.com/stripe/stripe-node/3.5-idempotency-and-retry-logic)
