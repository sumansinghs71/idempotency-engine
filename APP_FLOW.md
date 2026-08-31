# Phase 4 — Application Flow

The end-to-end lifecycle of a single `POST /charges` request through IdemEngine, in prose and as a numbered step list, tied directly to the state machine in `DESIGN.md §2`. Then: retry lifecycle from each possible existing state.

## 1. First-attempt happy-path lifecycle

A merchant client sends `POST /charges` with an `Idempotency-Key` header and a JSON body. The request enters the Spring Boot `DispatcherServlet`, hits `IdempotencyInterceptor`, which inspects the header. If the header is missing or malformed (empty, longer than 100 characters), the interceptor short-circuits with `400 Bad Request` and the request never reaches the controller. If present, the interceptor resolves the caller's `user_id` from the `X-User-Id` header — a development/demo identity shim, **not** authentication; it is unverified and anyone can set it, see README §7 — computes a SHA-256 fingerprint over the canonicalized JSON body, and stashes both on the request attributes. It then allows the request to continue to `ChargesController.create()`.

`ChargesController.create()` is a thin façade. It pulls the resolved values off the request attributes and delegates everything to `IdempotencyService.execute(userId, key, bodyHash, body, businessFn)`, where `businessFn` is a four-phase recipe for performing the charge. The controller's only job after that is to translate the service's `IdempotencyOutcome` (status code + JSON body) into a Spring `ResponseEntity`.

`IdempotencyService.execute()` opens the **first transaction (`tx1`)** with propagation `REQUIRES_NEW`. Inside `tx1` it issues a `SELECT … FROM idempotency_keys WHERE user_id = ? AND key = ? FOR UPDATE`. For a brand-new key, the SELECT returns nothing. The service inserts a row: `user_id`, `key`, `request_method='POST'`, `request_path='/charges'`, `request_params_hash`, `request_body`, `recovery_point='started'`, `locked_at=now()`, `attempt_no=1`, `expires_at=now() + interval '24 hours'`. It commits `tx1` and writes one `audit_logs` row in the same transaction (`action='key_created'`). The service now owns the row's `locked_at` lock until it explicitly clears it on the way out.

The service enters the **state-machine loop**. The loop reads `recovery_point` and dispatches to the matching phase function. For `started`, it calls `businessFn.validateAndCreateRide()` inside `tx2` (also `REQUIRES_NEW`). That phase resolves the user record, inserts a `rides` row with `stripe_charge_id = NULL` and `status='pending'`, and inserts an `audit_logs` row (`action='phase_committed', to_state='customer_validated'`). At the end of the phase, the same transaction sets `idempotency_keys.recovery_point='customer_validated'` and commits `tx2`. **The recovery-point write is part of the phase's transaction, not the outer service transaction** — this is the load-bearing detail that lets a crash between phases resume cleanly.

Back in the loop, `recovery_point` is now `customer_validated`. The service calls `businessFn.callPsp()`. This phase opens **no transaction yet** — the PSP call is outside any DB transaction, because we don't want to hold DB resources across the network. The service constructs the derived idempotency key (`"idem-" + idempotencyKeyRowId`) and calls `ExternalPaymentClient.charge(amount, customer, derivedKey)`. The client posts to the PSP with `Idempotency-Key: idem-42`. The PSP returns 200 with a `charge_id`. Now the service opens `tx3` (`REQUIRES_NEW`): updates `rides.stripe_charge_id`, inserts an `audit_logs` row (`action='phase_committed', to_state='external_api_called'`, `metadata={"derived_key":"idem-42","psp_charge_id":"ch_X"}`), sets `idempotency_keys.recovery_point='external_api_called'`, and commits. Note the ordering: PSP call first, *then* the DB commit. If the process dies after the PSP returns but before `tx3` commits (failure point F6), the retry path issues the PSP call again with the same derived key, the PSP returns the same `charge_id` from its own idempotency cache, and `tx3` commits with the correct value.

The loop reads `external_api_called`. The service calls `businessFn.finalize()`. This phase opens `tx4` (`REQUIRES_NEW`): inserts one `staged_jobs` row (`job_name='send_ride_receipt'`, payload with the ride and user id), builds the final response body, then performs a single UPDATE on `idempotency_keys` setting `response_code=201`, `response_body=<json>`, `recovery_point='finished'`, `locked_at=NULL`. One more `audit_logs` row (`action='phase_committed', to_state='finished'`). Commit `tx4`. The row is now durable, the lock is released, and any future retry will short-circuit to the cached response.

The loop reads `finished` and exits. The service returns `IdempotencyOutcome(responseCode=201, body=<json>)` to the controller, which writes the HTTP response. The `staged_jobs` row is picked up within 5 seconds by the job drain, which calls SMTP/Mailgun to send the receipt and deletes the row.

## 2. Step list (tied to the state machine)

The same flow as a tight numbered list:

```
1.  HTTP arrives → DispatcherServlet → IdempotencyInterceptor.
2.  Interceptor: validate header (400 on missing/invalid), resolve user_id, hash body, attach to request.
3.  ChargesController.create() → IdempotencyService.execute(userId, key, bodyHash, body, fn).
4.  tx1 (REQUIRES_NEW): SELECT FOR UPDATE → not found → INSERT key row (started, locked) + audit('key_created'). COMMIT.
5.  Loop: recovery_point == 'started' → fn.validateAndCreateRide() in tx2 (REQUIRES_NEW).
       INSERT rides(pending) + audit('phase_committed', to_state='customer_validated')
       + UPDATE keys.recovery_point='customer_validated'. COMMIT.
6.  Loop: recovery_point == 'customer_validated' → fn.callPsp():
       derivedKey = "idem-" + key.id  ← deterministic, computed from row id
       PSP.charge(amount, customer, derivedKey) → charge_id=ch_X
       tx3 (REQUIRES_NEW): UPDATE rides.stripe_charge_id=ch_X
                         + audit('phase_committed', to_state='external_api_called',
                                 metadata={derived_key, psp_charge_id})
                         + UPDATE keys.recovery_point='external_api_called'. COMMIT.
7.  Loop: recovery_point == 'external_api_called' → fn.finalize() in tx4 (REQUIRES_NEW).
       INSERT staged_jobs(send_ride_receipt, …) ON CONFLICT DO NOTHING
       + UPDATE keys SET response_code=201, response_body=…, recovery_point='finished', locked_at=NULL
       + audit('phase_committed', to_state='finished'). COMMIT.
8.  Loop: recovery_point == 'finished' → break.
9.  Return IdempotencyOutcome(201, body) → controller → HTTP 201.
10. Job drain picks up staged_jobs row, sends receipt, deletes row.
```

## 3. Retry lifecycle, by existing state

A retry is *any* second arrival of `(user_id, key)`. What happens depends entirely on what the row says.

### 3.1 Row does not exist

This is just the first-attempt path. Step 4 inserts a fresh row.

### 3.2 Row exists, `recovery_point='finished'`, body hash matches

The fast cached-replay path. Step 4 SELECTs the row, sees `finished`, returns `IdempotencyOutcome(row.response_code, row.response_body)` immediately. No phase functions run. No PSP call. No new audit row (or one with `action='cache_hit'` if observability is desired — we write it; it's cheap and useful). Single indexed read + one HTTP write. p99 target: 10ms.

### 3.3 Row exists, `recovery_point='finished'`, body hash mismatch

Step 4 SELECTs, sees `finished`, compares hashes, mismatch. Returns `IdempotencyOutcome(422, {"error":"idempotency_key_body_mismatch"})`. Writes audit (`action='body_mismatch'`). Lock is not held (`finished` rows are unlocked); nothing to release.

### 3.4 Row exists, not `finished`, `locked_at` fresh (< 90s)

A peer process is currently working this request. We do not race them. Step 4 SELECTs, sees the fresh lock, returns `IdempotencyOutcome(409, {"error":"idempotency_request_in_progress","retry_after_ms":500})`. Writes audit (`action='lock_conflict'`). The Stripe SDK retries on 409 with backoff, so the client transparently re-enters; by then the holder has either finished (→ cached path 3.2) or died (→ stale-lock path 3.5).

### 3.5 Row exists, not `finished`, `locked_at` stale (>= 90s)

The previous holder is presumed dead. Step 4 SELECTs, sees stale lock. In the same `tx1` it issues `UPDATE keys SET locked_at = now(), attempt_no = attempt_no + 1`, commits, then drops into the state-machine loop **starting from whatever `recovery_point` was committed**. Each subsequent phase only runs if the recovery point is at-or-before it. Because every recovery point was committed atomically with the local mutations of its phase, the on-disk state is consistent with the recovery point — there is no half-written ride row, no half-written charge id.

The PSP call (Step 6) is the only one with external side effects, and it is protected by the derived idempotency key. So a retry from `customer_validated` re-issues the PSP call with the same derived key; the PSP returns the same charge_id from its idempotency cache (or, if the original call never reached the PSP, creates the charge for the first time). Either way, the charge_id we commit to `rides` is the unique correct one.

### 3.6 Row exists, not `finished`, body hash mismatch

Step 4 SELECTs, body mismatch. Returns 422 immediately. We do not advance state, do not touch `locked_at`. The audit row carries enough metadata to diagnose the merchant's bug.

### 3.7 Row exists but expired (`expires_at < now()`)

The reaper may not have gotten to it yet. We treat it as nonexistent: Step 4's SELECT predicates on `expires_at > now()`, so the lookup returns nothing, and we proceed as in 3.1 — INSERT a fresh row. The reaper will delete the stale row on its next pass.

## 4. Edge case: the request that runs over 90 seconds

If a legitimate request takes more than 90 seconds (say, the PSP is very slow), a retry would observe a stale lock and start resuming. This would be a bug — we'd then have two concurrent processes both doing the request.

Mitigation: the `locked_at` staleness threshold (90s) is calibrated to be slightly longer than the PSP timeout (80s). The PSP client enforces an 80s hard timeout; if it doesn't return by then, the request fails with 503 and the lock is released *before* any retry could pick up the row as stale. The 10-second buffer absorbs JVM stop-the-world pauses, slow GC, and similar.

For pathological cases (full JVM hang > 90s), a `lock_extend` heartbeat job could refresh `locked_at` every 30 seconds while the phase is active. v1 does not implement this; the 80s timeout + 90s threshold has been adequate in similar systems.

## 5. The four `@Transactional` boundaries — annotation map

```
IdempotencyService.execute(...)           → no @Transactional (orchestrator only)
  acquireOrServe(...)                      → @Transactional(propagation = REQUIRES_NEW)        ← tx1
  runStartedPhase(...)                     → @Transactional(propagation = REQUIRES_NEW)        ← tx2
  runExternalCallPhase(...)
    PSP HTTP call                          → no transaction (foreign side effect)
    persistExternalCallResult(...)         → @Transactional(propagation = REQUIRES_NEW)        ← tx3
  runFinalizePhase(...)                    → @Transactional(propagation = REQUIRES_NEW)        ← tx4
```

Every method that mutates DB state is `REQUIRES_NEW`. This guarantees that even if the outer `execute(...)` ever ends up wrapped in a caller's transaction, each phase still commits independently and crash-resumption is safe.

If a phase throws, Spring rolls back *only that phase's transaction*. The recovery point persisted by the previous phase is untouched. On retry, we resume from that recovery point. That is the entire point of `REQUIRES_NEW` here; using `REQUIRED` or letting Spring auto-wrap would be a correctness bug.

---

**Sources:**

- [Implementing Stripe-like Idempotency Keys in Postgres — brandur.org](https://brandur.org/idempotency-keys)
- [Designing robust and predictable APIs with idempotency — Stripe](https://stripe.com/blog/idempotency)
- Spring Framework Reference, *Transaction Propagation*.
