# Phase 0 — Research Notes

Sources read end-to-end before any design or code:

1. Stripe Engineering, *Designing robust and predictable APIs with idempotency*, Brandur Leach, 2017 — [stripe.com/blog/idempotency](https://stripe.com/blog/idempotency).
2. Brandur Leach, *Implementing Stripe-like Idempotency Keys in Postgres*, 2017 — [brandur.org/idempotency-keys](https://brandur.org/idempotency-keys) (and the reference repo `brandur/rocket-rides-atomic`).
3. DeepWiki, *Idempotency and Retry Logic* for `stripe/stripe-node` — [deepwiki.com/stripe/stripe-node/3.5-idempotency-and-retry-logic](https://deepwiki.com/stripe/stripe-node/3.5-idempotency-and-retry-logic). Cross-checked against the Brandur post.
4. Kleppmann, *Designing Data-Intensive Applications* (DDIA), Chapter 8 ("The Trouble with Distributed Systems") on unreliable networks and partial failure, and Chapter 11 ("Stream Processing"), §"Fault Tolerance → Atomic commit revisited" and §"Idempotence", on how exactly-once semantics is really effectively-once built on idempotent operations + atomic state transitions.

---

## 1. Why a multi-state recovery key, not a "seen / not seen" boolean

A boolean `seen` flag answers exactly one question: "have I started this request before?". It cannot tell me **how far** the previous attempt got, and that is the only question that matters when the previous attempt crashed.

Concretely, in a charge endpoint there are at least three points where the world changes:

- We commit local rows (ride, audit log).
- We call the external PSP (Stripe) — a *foreign state mutation* that we cannot roll back.
- We commit the PSP's returned charge ID back to our DB and queue downstream side effects (email receipt).

If the only thing the key stores is `seen=true`, a retry has to make a choice with no information: skip the request (and risk leaving the customer charged with no row in our DB), or re-run it (and risk double-charging). Both are wrong.

Brandur's insight, taken directly from Stripe's production design, is to model the request as a **directed acyclic state machine of recovery points**, each one corresponding to the boundary *between* two foreign state mutations or after an atomic group of local mutations:

```
started → ride_created → charge_created → finished
```

Each phase commits its recovery point in the same DB transaction as its local mutations, so the recovery point is a durable cursor into the request's progress. On retry, the server reads the recovery point and jumps into the matching phase. A retry of a `charge_created` request never re-issues the PSP charge — it only finishes the bookkeeping. A retry of a `finished` request short-circuits and replays the cached response.

A naive `seen` flag also can't distinguish "in progress right now" from "completed". The Stripe / Brandur model encodes both: `recovery_point` says how far we got, `locked_at` says whether someone is currently working on it, and the combination is what lets a concurrent retry safely return `409` while a crashed retry safely resumes.

This matches DDIA's framing of exactly-once semantics (Ch. 11): you do not get true exactly-once delivery from the wire; you get **effectively-once** by combining (a) at-least-once retries from the client with (b) idempotent, restartable state transitions on the server. The recovery-point cursor is precisely the server-side state that makes (b) possible.

## 2. The client/server contract

Both Stripe's post and Brandur's post specify the contract, though piecewise. Pulled together:

**Client guarantees:**

- Generates an idempotency key with sufficient entropy (UUIDv4 or equivalent) and uses it **per logical operation**, not per HTTP attempt. Stripe's SDK uses the form `stripe-node-retry-{uuid}` for its own auto-generated keys (per DeepWiki on `stripe-node`).
- Sends the *same key* on every retry of the *same logical request*, with *the same body*. Sending the same key with a different body is a client bug; the server is allowed to reject it.
- Retries on ambiguous failures: connection errors, 5xx, and 409. Stripe's SDK retries on `ECONNRESET`, `ECONNREFUSED`, `ENOTFOUND`, `EPIPE`, `ETIMEDOUT`, `ECONNABORTED`, plus any 409 and any 5xx, with exponential backoff and jitter (initial 0.5s, cap 5s, factor of `2^(n-1)`, multiplied by a random `[0.5, 1.0]` factor). It honors `Retry-After` up to 60s.
- Treats a 2xx response as the final outcome — it does *not* retry past success.

**Server guarantees:**

- For a request with a known key in `finished` state: returns the **byte-identical** cached response (same status, same body) without re-executing side effects.
- For a request with a known key currently being processed (`locked_at` recent): returns `409 Conflict` indicating "in progress", which the client should retry after backoff.
- For a request with a known key and a *mismatched body*: returns `422 Unprocessable Entity` — this is a client bug, not a transient failure, and must not be retried with the same key.
- For a request with a key that exists in a non-`finished` state whose lock has expired (or was released by a crash): acquires the lock and resumes from the stored recovery point.
- For an unknown key: creates the row in `started`, locks it, and begins phase 1.
- Scopes keys per authenticated principal: `(user_id, key)` is unique, *not* `key` alone. A key leaked by one user is useless to another.

The contract is asymmetric on purpose. The client only has to "send the same key on retry"; the server absorbs all the complexity of state, locking, recovery, and replay.

## 3. Nested idempotency — why the external call needs its own derived key

This is the failure mode that breaks naive implementations.

Suppose the server's call to the PSP (Stripe) succeeds — the customer's card is charged — but the response packet is lost on the way back. Our server's process dies, or its HTTP client times out, before the recovery point can be committed. The client (correctly) retries. Our server (correctly) resumes from `ride_created`. It calls the PSP again.

If that PSP call does **not** carry its own idempotency key, the PSP has no way to know it is the same logical charge. It happily charges the customer a second time. We have just defeated the entire purpose of the idempotency-key system: the outer key prevented us from creating a second `Ride` row, but the inner call still produced a second charge.

The fix, used in Brandur's reference implementation, is to **derive the downstream idempotency key from a stable identifier on the inbound key row**. Brandur uses `"rocket-rides-atomic-#{key.id}"`. The properties that matter:

- It is deterministic — the same inbound key always produces the same downstream key, no matter how many times the request is retried or which server process handles it.
- It is unique across our tenants — derived from our internal row id, not from the user-supplied key, so two users using the string `"abc"` do not collide at the PSP.
- It is opaque to the client — the client cannot influence it, so a malicious or buggy client cannot cause downstream collisions.

DDIA Ch. 11 generalizes this: every step in a multi-system pipeline that has external side effects must be individually idempotent, with an identifier that survives retries, or you don't have exactly-once semantics for the pipeline — you only have it for the steps you happened to think about.

A subtler corollary: the derived key must be computed **before** the first attempt, not regenerated per attempt. If you UUID it inside `ExternalPaymentClient.charge()`, every retry will pick a fresh UUID and the PSP will charge again. The derived key is a function of the inbound key row, period.

## 4. DeepWiki cross-reference

The `stripe-node` DeepWiki page describes the client-side mirror of this design:

- SDK auto-generates an idempotency key for every mutating request (`stripe-node-retry-{uuid}`) when retries are enabled, so even users who forget to pass one get retry-safety. We don't auto-generate server-side — we require the header, because the *logical* identity of a request is something only the client knows.
- The SDK retries on 409 ("conflict / in-progress") in addition to 5xx. This is the wire-level signal that pairs with Brandur's server-side "key currently locked → 409" — they are two halves of the same contract.
- The SDK caps the `Retry-After` honored at 60s, with exponential backoff capped at 5s. We use the same backoff philosophy on the client side of our `ExternalPaymentClient`.

Reference implementations on GitHub (the Medusa Node/Express implementation and Gröber's Go/Postgres port) both follow the same state-machine + recovery-point + per-user-scoped-key pattern. Where they diverge from Brandur is mostly in concurrency primitive: Medusa uses an in-memory mutex per key (only correct on a single process), the Go port uses `SELECT ... FOR UPDATE`. We adopt `SELECT ... FOR UPDATE` paired with `locked_at` for staleness detection — see TRD §"Concurrency control".

## 5. The ~8 failure points we must recover from

Enumerated up front so the state machine and tests can map onto them. Each is referenced in DESIGN.md §"Failure-point inventory" and has a corresponding chaos test in Phase 8.

| # | Failure point | What can go wrong | How we recover |
|---|---|---|---|
| F1 | Before the DB write of the key row | Client connection drops | Client retries; key created cleanly on next attempt. |
| F2 | After key row created, before fingerprint check completes | Crash mid-validation | Retry re-runs fingerprint check; no side effects to undo. |
| F3 | After `started` → before `customer_validated` | Crash mid-local-bookkeeping | Atomic phase commits or rolls back as a unit; retry replays it. |
| F4 | After local bookkeeping, before the external PSP call | Crash | Resume from `customer_validated`; external call carries derived key. |
| F5 | During the external PSP call | Network timeout, connection reset, server dies waiting | Retry; PSP dedupes on derived key — no double charge. |
| F6 | After PSP success, before recovery-point commit | Process killed between receiving 200 and `COMMIT` | Retry hits PSP again with same derived key → cached PSP response → commit `external_api_called`. |
| F7 | After `external_api_called`, before response persistence | Crash during receipt enqueue | Retry replays the (transactionally-staged) job insert; idempotent because key+phase. |
| F8 | After response stored, before HTTP response reaches client | Connection dies on the way out | Retry sees `finished` and returns cached response. |

## Gate

I can explain the multi-state rationale (it encodes *progress*, not just *seen-ness*, and progress is what lets us resume safely past a foreign state mutation), the client/server contract (asymmetric: client sends the same key on retry; server caches, locks, resumes, or rejects), and nested idempotency (every foreign state mutation must carry a derived, deterministic key so a retried outer request cannot produce a duplicated inner side effect). **Phase 0 gate passed.**

---

**Sources:**

- [Designing robust and predictable APIs with idempotency — Stripe](https://stripe.com/blog/idempotency)
- [Implementing Stripe-like Idempotency Keys in Postgres — brandur.org](https://brandur.org/idempotency-keys)
- [Idempotency and Retry Logic — stripe/stripe-node, DeepWiki](https://deepwiki.com/stripe/stripe-node/3.5-idempotency-and-retry-logic)
- Kleppmann, *Designing Data-Intensive Applications*, Ch. 8 ("The Trouble with Distributed Systems") and Ch. 11 ("Stream Processing", §"Fault Tolerance" and §"Idempotence").
