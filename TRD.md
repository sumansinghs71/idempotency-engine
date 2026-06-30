# Phase 2 — Technical Requirements Document

> Companion to PRD.md. This document covers stack, the seven load-bearing design decisions, the API contract, retention/security, and observability.

## 1. Stack & versions

| Component | Choice | Why |
|---|---|---|
| Language / runtime | **Java 17 (LTS)** | LTS through 2029; records, sealed types, pattern matching; widely deployed in payments. |
| Framework | **Spring Boot 3.2.x** | Mature HTTP + JPA + transaction management; `HandlerInterceptor` and `@Transactional` propagation semantics are exactly what the design needs. Jakarta EE 9+ migration is done. |
| Web | Spring Web (Tomcat) | Stable. Reactive (WebFlux) buys nothing here — the bottleneck is the DB lock + the PSP call. |
| Persistence | Spring Data JPA + Hibernate 6.x | We need `@Transactional` with `Propagation.REQUIRES_NEW` for recovery-point commits; JPA gets us that with one annotation. |
| DB | **PostgreSQL 15+** | ACID with `SERIALIZABLE`, native `JSONB`, row-level `SELECT … FOR UPDATE`, transactional advisory locks, generated columns. Decision §2.1 below. |
| Migrations | **Flyway 10.x** | Versioned migrations checked into source; production-grade. |
| Build | **Gradle 8.x (Kotlin DSL)** | Faster than Maven, dependency-locking, JMH plugin support. |
| Test | JUnit 5, AssertJ, **Testcontainers 1.20**, Awaitility, **Toxiproxy** sidecar | Real Postgres for integration; Toxiproxy injects PSP-side network failures. |
| Bench | **JMH 1.37** | Standard JVM micro/throughput benchmarking. |
| Observability | Micrometer + Logback JSON | Stdlib, prod-typical. |

## 2. Design decisions (alternatives + rationale)

### 2.1 Storage backend — Postgres

| Option | Pros | Cons |
|---|---|---|
| **Postgres (chosen)** | ACID; `SELECT … FOR UPDATE` gives row-level locking *in the same transaction* as the work, so the lock and the recovery-point commit are atomic. JSONB for request/response. Mature operational story. This is what Stripe used (per Brandur). | Higher per-op latency than Redis; needs careful TX boundaries (which we want anyway). |
| Redis (TTL + Lua) | Sub-millisecond; native TTL. | Not ACID with the rest of our state. To do recovery points atomically we'd need a separate WAL or to put *all* of `rides`/`audit_logs` in Redis too. Stripe's own writeup explicitly grounds the design in ACID semantics. |
| DynamoDB conditional writes | Auto-scaling; TTL built in. | Single-region strong consistency is fine, but cross-row transactions (key + ride + audit) require DynamoDB Transactions, which costs 2× WCUs and still doesn't give us `FOR UPDATE`-style blocking. Optimistic concurrency works but maps poorly to "second concurrent request should block and return cached result". |

**Decision:** Postgres. The single most important property is "the recovery point commits atomically with the local mutations of its phase". That is a transaction. Redis and Dynamo can be made to work, but at the cost of inventing a worse transaction layer on top of them.

Cites: [Brandur §"The idempotency key relation"](https://brandur.org/idempotency-keys), DDIA Ch. 11 §"Atomic commit revisited".

### 2.2 Key uniqueness scope — per-user

`UNIQUE (user_id, key)`, not `UNIQUE (key)`.

- **Bug prevented:** two merchants generating the string `"abc"` (or worse, a UUID collision across tenants — vanishingly rare but the code shouldn't depend on it) do not collide.
- **Security implication:** a leaked idempotency key cannot be replayed by a different authenticated principal. The header alone doesn't authenticate anything; the `(user_id, key)` lookup combines the authenticated principal with the key, so the worst case of a leaked key is "the owner's own request gets replayed", which is the same as the owner retrying — i.e., the design's normal happy path.

Brandur calls this out explicitly: "We've made `idempotency_key` unique, but across `(user_id, idempotency_key)` so that it's possible to have the same idempotency key for different requests as long as it's across different user accounts."

### 2.3 Request fingerprinting — SHA-256 of canonicalized body

Stored as `request_params_hash CHAR(64)`. The full body also goes in `request_body JSONB` for debugging.

- **Canonicalization:** sort JSON object keys lexicographically; strip insignificant whitespace; UTF-8 encode; SHA-256.
- **Why:** we have to detect "same key, different body" to return 422. We could compare full bodies, but (a) JSONB equality is order-sensitive depending on how it's stored, and (b) hash comparison is O(1) for any body size.
- **Bug class prevented:** the client iterates on their request body in dev, accidentally reuses the same idempotency key across two distinct intents, and our server obediently caches the *first* one and returns it on the second — silently doing the wrong thing. The 422 makes the bug loud.

### 2.4 Expiry — 24h default, configurable

- **Storage tradeoff:** at the 5,000 req/s NFR, 24h ≈ 2.1 GB; 72h ≈ 6.3 GB. Both fit comfortably; the deciding factor is contract clarity.
- **Contract tradeoff:** 24h matches Stripe's documented public contract, so merchant integrators have a single number to plan around. Brandur recommends 72h to survive a Friday-night incident. We pick **24h default, configurable to 72h** for ops to bump in incidents.
- **Effect on FR-6:** an expired key behaves as a brand-new key. The reaper, not the request path, is responsible for deleting expired rows. The request path uses `WHERE expires_at > now()` to ignore them.

### 2.5 Concurrency control — `SELECT … FOR UPDATE` + `locked_at`

Two candidates, both viable:

| Option | Pros | Cons |
|---|---|---|
| **`SELECT … FOR UPDATE` (chosen)** | Row-level lock held for the duration of the current transaction; releases automatically on `COMMIT`/`ROLLBACK`/process death (Postgres releases all session locks on connection close). Pairs naturally with our per-phase TX. Inspectable via `pg_locks`. | Lock held for as long as the transaction is open — if our phase TX is long, contention rises. We keep phases short. |
| Postgres advisory locks (`pg_advisory_xact_lock`) | Single 64-bit hash of `(user_id, key)`; constant-time. | Cannot return information about *who* holds it; debugging is harder; collision-prone if the hash is sloppy. |

We pick `SELECT … FOR UPDATE` on the `idempotency_keys` row, fetched inside the per-phase transaction. To handle the case where a holder dies *between transactions* (e.g., between phase 2's commit and phase 3's begin), we also store `locked_at TIMESTAMPTZ`:

```sql
SELECT * FROM idempotency_keys
 WHERE user_id = ? AND key = ?
   FOR UPDATE;
-- then in code: if locked_at IS NOT NULL AND locked_at > now() - INTERVAL '90 seconds'
--                → 409 in_progress
--                else acquire: UPDATE … SET locked_at = now()
```

The `90 seconds` is a conservative lock-staleness threshold tuned from `request.timeout = 80s` (matching Stripe's SDK default) + buffer. Any holder still alive after 90s has effectively died.

**Deadlock avoidance:** we always lock exactly one row, identified by a unique key, in a single transaction. No second resource is locked while holding this one (the `rides` insert is in a *different*, nested `REQUIRES_NEW` transaction that takes its own row's lock as needed). So no two transactions can ever hold locks in opposite order — deadlock-impossible.

### 2.6 Recovery-point granularity

Phases (DAG, never cycle backward):

```
started → customer_validated → external_api_called → finished
```

| From | To | What runs in this phase | Why it gets its own commit |
|---|---|---|---|
| (none) | `started` | INSERT the key row (or attach to existing). | Establishes the bookkeeping cursor before any side effects. |
| `started` | `customer_validated` | SELECT user; INSERT `rides` (status=`pending`, no charge id); INSERT `audit_logs(action='ride_created')`. | All local; can be rolled back as a unit on error. Committed to make the ride row durable before we leave our system boundary. |
| `customer_validated` | `external_api_called` | Call PSP with derived idempotency key `idem-${key_id}`. On success, UPDATE `rides.stripe_charge_id`; INSERT `audit_logs(action='charge_created')`. | The PSP call is the foreign state mutation. Its outcome (the charge id) must be committed before we report success to anyone. |
| `external_api_called` | `finished` | INSERT `staged_jobs(send_receipt, …)`; build response JSON; UPDATE the key row's `response_code`, `response_body`, `recovery_point='finished'`, `locked_at=null`. | Transactionally-staged: the email job and the response cache commit together. After this, the response can be served from cache. |

The granularity rule, lifted from Brandur §"Designing atomic phases": (a) upserting the key gets its own phase; (b) every foreign state mutation gets its own phase; (c) all local operations between them group into one phase each.

**Crucially:** each phase opens a *separate transaction* (`Propagation.REQUIRES_NEW`). Recovery-point commits must NOT be rolled back with the business transaction — if phase 3 fails after phase 2 committed `customer_validated`, retry must resume at phase 3, not redo phase 2.

### 2.7 Nested idempotency for the external call

Derived key formula: `idem-${idempotencyKey.id}` (BIGINT primary key of the inbound row).

- **Deterministic:** the row id is assigned once on INSERT; every retry references the same row, so every retry of the same logical request constructs the same derived key.
- **Per-tenant unique:** the BIGINT is globally unique in our DB, so two tenants using the same inbound key string produce different derived keys.
- **Opaque to client:** the client never sees it.

The `ExternalPaymentClient.charge(amount, customer, derivedIdempotencyKey)` call must accept the derived key as a *required* parameter (no defaulting) so accidental omission is a compile error, not a runtime bug.

## 3. API contract

### 3.1 `POST /charges`

**Request:**

```http
POST /charges HTTP/1.1
Idempotency-Key: 0ccb7813-e63d-4377-93c5-476cb93038f3
Authorization: Bearer <token>     # resolves to user_id
Content-Type: application/json

{ "amount": 2000, "currency": "usd", "customer_id": "cus_A8Z5MHwQS7jUmZ" }
```

**Successful response (201):**

```http
HTTP/1.1 201 Created
Content-Type: application/json

{
  "ride_id": "rid_01HXYZ…",
  "amount": 2000,
  "currency": "usd",
  "charge_id": "ch_3PaQ…",
  "status": "succeeded"
}
```

### 3.2 Error responses

| Code | When | Body |
|---|---|---|
| `400 Bad Request` | Missing `Idempotency-Key` header, or key empty/too long (>100 chars). | `{"error":"idempotency_key_required"}` or `{"error":"idempotency_key_invalid"}` |
| `409 Conflict` | Same `(user_id, key)` is currently being processed (lock held, `locked_at` fresh). | `{"error":"idempotency_request_in_progress","retry_after_ms":500}` |
| `422 Unprocessable Entity` | Same `(user_id, key)` exists with a different body fingerprint. | `{"error":"idempotency_key_body_mismatch"}` |
| `402 Payment Required` | PSP returned a definitive card-decline. Cached on the key. | `{"error":"card_declined","decline_code":"…"}` |
| `503 Service Unavailable` | Transient PSP/DB failure during processing. Lock released; client should retry with the same key. | `{"error":"temporarily_unavailable"}` |
| `500 Internal Server Error` | Unhandled. Lock released. Same client retry behavior. | `{"error":"internal"}` |

`Retry-After-Ms` (custom header) returned on 409 and 503; respects Stripe SDK retry-after semantics (capped at 60s).

### 3.3 Idempotent replay rules

- `(user_id, key)` exists, `finished`, body matches → 200 OK + the cached `response_code` and `response_body`. (The cached code may itself be a 4xx, e.g., a definitive 402 card decline; we replay that faithfully.)
- `(user_id, key)` exists, not `finished`, lock fresh → 409.
- `(user_id, key)` exists, not `finished`, lock stale (>90s) → resume from `recovery_point`.
- `(user_id, key)` exists, body mismatch → 422 regardless of state.
- Not exists OR exists-but-expired → treat as new.

## 4. Data retention, security, PII

- **Retention:** 24h default for idempotency key rows. The reaper job runs hourly: `DELETE FROM idempotency_keys WHERE expires_at < now()`. `rides` rows are kept indefinitely (business record); the FK from `rides.idempotency_key_id` is `ON DELETE SET NULL` so reaping doesn't cascade.
- **PII in `request_body`:** for `POST /charges` the body contains `customer_id` (an opaque token, not a PAN/CVV) and amount/currency. No PII per PCI scope. The body is JSONB; we don't index it; column-level encryption (pgcrypto) is left to follow-up if business requires it.
- **Audit retention:** `audit_logs` rows are retained 90 days minimum (separate reaper).
- **Logs:** idempotency key value is logged at INFO. It is not a secret on its own (it's just a UUID); combined with auth it allows replay, so we treat it as low-sensitivity but not log to third-party log aggregators that we don't trust.

## 5. Observability

### 5.1 Audit log table

Every state transition produces one row:

```sql
audit_logs (
  id BIGSERIAL PK,
  idempotency_key_id BIGINT,  -- FK with ON DELETE SET NULL
  user_id BIGINT,
  action VARCHAR(50),         -- 'key_created','phase_committed','phase_failed','cache_hit','body_mismatch','lock_conflict','reaped'
  from_state VARCHAR(50),
  to_state VARCHAR(50),
  attempt_no INT,
  metadata JSONB,             -- {"derived_key":"idem-123","psp_charge_id":"ch_..."}
  created_at TIMESTAMPTZ DEFAULT now()
)
```

This is the on-call's primary diagnostic. If we ever ship a duplicate charge, the audit log lets us prove which phase ran twice and from which attempt — a property we got from a multi-state recovery model that a `seen` boolean could not provide.

### 5.2 Metrics (Micrometer)

| Metric | Type | Tags | Use |
|---|---|---|---|
| `idem.requests.total` | counter | `state` (cache_hit, new, resumed, lock_conflict, body_mismatch) | Topline traffic mix. |
| `idem.cache_hits.total` | counter | — | Tracks the "cheap" path. |
| `idem.lock_wait.duration` | timer | — | Detects contention hotspots. |
| `idem.recovery.total` | counter | `from_state` | Should be near zero in steady state; non-zero = crashes are happening. |
| `idem.body_mismatch.total` | counter | — | Detects merchant bugs. |
| `idem.external_call.duration` | timer | `outcome` (success, declined, timeout, error) | PSP performance. |
| `idem.reaper.rows_deleted` | counter | — | Reaper keeping up. |

### 5.3 Structured logging

JSON to stdout; every log line carries `idempotency_key`, `user_id`, `recovery_point`, `attempt_no`, `trace_id`. Correlatable with the audit table by `idempotency_key_id` + `attempt_no`.

---

**Sources:**

- [Designing robust and predictable APIs with idempotency — Stripe](https://stripe.com/blog/idempotency)
- [Implementing Stripe-like Idempotency Keys in Postgres — brandur.org](https://brandur.org/idempotency-keys)
- [Idempotency and Retry Logic — stripe/stripe-node, DeepWiki](https://deepwiki.com/stripe/stripe-node/3.5-idempotency-and-retry-logic)
- Kleppmann, *DDIA*, Ch. 11 §"Atomic commit revisited" and §"Idempotence".
