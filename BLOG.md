# Building Stripe's idempotency system from scratch: what I learned about distributed failure

I spent a week building a Stripe-style idempotency middleware from first principles — server-side, Postgres-backed, Spring Boot. The brief was simple: a client sends `POST /charges` with an `Idempotency-Key` header, anything in the world can go wrong between the request leaving their machine and the response arriving back, and the customer must be charged exactly once. Same key on retry, same response, no second charge, no support ticket. That's it.

Cursorily, the design looks obvious. You write down the key, you check it before processing, you cache the response. A junior engineer could sketch the table in five minutes. But the production-grade version — the one that survives 100 concurrent retries to the same key, recovers from a process death between any two SQL statements, and never causes a double-charge no matter what point in the request lifecycle the network drops — is much harder than that, and it's interesting precisely because of where the difficulty hides.

I'd read [Stripe's 2017 blog post](https://stripe.com/blog/idempotency) and [Brandur Leach's companion post on the Postgres implementation](https://brandur.org/idempotency-keys) several times, but had never tried to write the thing myself. What follows are the moments where my mental model broke, and the design decisions that fixed it. If you only take one thing from this post, take this: **a single boolean "seen this request" is nowhere near enough**, and the rest of this post is a slow elaboration on why.

## The first wrong instinct: a `seen` flag

My first sketch was, of course, the obvious one. A table with `(key, response)`, a flag for "processed". The pseudocode was four lines: `if exists and processed → return cached response; if exists and processing → 409; else → process and cache.`

This works for the cases where nothing goes wrong, which is to say it works for the cases that don't need idempotency. The moment you imagine the process dying during the third line — between the moment your code calls the external payment service and the moment the response is durably written to the database — the design falls apart. Did the payment go through? Maybe. Did the next attempt see your `processing` flag and 409? Yes. Will the next attempt eventually try again? Yes. Will it call the payment service again? Yes, because your `processing` flag doesn't know how far you got — only that you started.

The actual customer gets charged twice. You have to know more than "have I seen this?". You have to know *what I'd already done* when I died.

Brandur frames this beautifully: there's no single transaction that contains the work, because the work crosses a system boundary you don't own. You can't roll back a Stripe charge by rolling back a Postgres transaction. So you have to break the work into atomic *phases*, persist a cursor that tells you which phase you finished, and design the phase that touches the foreign system so that running it twice is no different from running it once. Three ideas; load-bearing in different ways.

I'd glossed past this in my reading. Sitting at a keyboard trying to write the actual code surfaced it instantly.

## The second wrong instinct: one transaction to rule them all

Once I accepted that the work needed phases, my next instinct was to wrap the *whole* operation in a single `@Transactional` block — including the recovery-point updates — and trust Spring to roll back everything coherently on failure. Clean. One annotation.

It is also exactly wrong. Suppose phase 2 (call the PSP) succeeds, phase 3 (commit the result to my DB) fails. If the whole thing is one transaction, Spring rolls back the recovery-point write *that said phase 2 succeeded*. The next attempt sees a recovery point that thinks phase 2 hasn't happened yet, calls the PSP again, and — if the PSP doesn't have its own idempotency layer keyed correctly — charges the customer a second time.

The recovery-point commits are not part of the business transaction. They are the cursor that says "this much, durably, has happened in the real world." They must outlive any rollback of the work that follows. Which means in Spring's vocabulary, every phase needs its own transaction, and the easiest way to enforce that is `@Transactional(propagation = REQUIRES_NEW)` on every phase method. Each phase commits its own work *and* its own recovery-point write together; the next phase starts a fresh transaction with the previous phase's commit fully on disk.

I caught myself almost making this mistake twice while wiring up the code. It's a quietly catastrophic error — you'd never see it in unit tests; it only shows up in chaos tests that kill the JVM at the wrong instant. I'd not have caught it without writing those tests.

## Nested idempotency, or: the bug that defeats the whole system

The most counterintuitive part of all of this is the realization that an idempotency layer in front of an external system that doesn't itself have an idempotency layer is fundamentally broken. You can't fix this with more retries; you can't fix it with better logging; you can only fix it by demanding that the downstream call carry an idempotency key too.

Concretely: my server has its own idempotency key on `POST /charges`. The server stores it, locks on it, caches the response on it. Inside my service, I call out to the PSP — say, Stripe — to actually charge the card. If the PSP call is just `charge(amount, customer)` with no idempotency, then here's the failure that bites: the PSP charges the customer, the response packet is lost on the way back, my process dies before I commit the recovery point. The client retries (correctly). My server resumes from "PSP call not yet made" (correctly!). I call the PSP again. The PSP, with no key to dedupe on, charges the customer a second time.

The outer idempotency layer prevented me from inserting a second `Ride` row in *my* database, but the inner call still produced a second charge in the *PSP's* database. The customer is double-charged and the entire system failed.

The fix is to pass a derived idempotency key into the PSP call — a key that's deterministically computed from something stable on the inbound request, so every retry produces the same downstream key. Brandur uses `"rocket-rides-atomic-#{key.id}"`, where `key.id` is the BIGINT primary key of the inbound `idempotency_keys` row. I copied this directly. The derivation has three properties that matter:

1. **Deterministic across retries** — the row id is assigned once at INSERT, and every retry references the same row, so every attempt at the PSP call carries the same derived key.
2. **Globally unique within my system** — the BIGINT is a database primary key, so two merchants who happen to use the same inbound key string produce different downstream keys.
3. **Opaque to the client** — the client never sees it; they cannot influence it; a malicious client cannot cause downstream collisions.

The subtler corollary: the derived key has to be computed *before* the call, not regenerated *inside* the client. If the `ExternalPaymentClient.charge()` method ever generated its own UUID on each call, every retry would pick a fresh UUID and every retry would be a fresh charge. I made the derived key a *required* parameter on the method — accidental omission is a compile error, not a runtime bug. That's a small ergonomic detail that matters enormously.

## The lock that has to expire

Concurrency was the next thing that surprised me. The naïve mental model is: "row exists with `processing=true`, return 409, the holder will finish eventually." This works fine if no process ever dies. If a process *does* die, the row sits with `processing=true` forever, every retry returns 409, and the customer's request never completes. Wonderful.

So you need a way to reclaim a stuck row. Brandur's pattern, which I followed: store a `locked_at TIMESTAMP`, and define a staleness threshold (90 seconds in my implementation). A row whose `locked_at` is fresh is genuinely being worked; a stale row's holder is presumed dead and the next entrant reclaims it.

The threshold is calibrated by the longest legitimate request: my PSP client has an 80-second timeout (matching the Stripe SDK's default), and I gave myself a 10-second buffer for GC pauses, OS context switches, and similar. If a phase legitimately takes longer than 90 seconds, two retries will start running it in parallel, and that's a correctness bug — so the threshold has to be strictly greater than the worst-case real latency. The clean alternative for very long phases is a lock-extend heartbeat that bumps `locked_at` every 30 seconds while the phase runs; I sketched it but didn't ship it for the v1. The numbers I have work for the workload I'm targeting.

The `locked_at` mechanism pairs with `SELECT … FOR UPDATE` on the row, but it's important to understand they're different layers of mutex doing different work. `SELECT … FOR UPDATE` is a single-transaction Postgres lock — it prevents two transactions from reading-then-writing the same row concurrently *within* a transaction. `locked_at` is a *cross-transaction* lock — it tells the next entrant whether a previous entrant is mid-request *between* transactions. You need both because the request lifecycle spans multiple transactions.

## The audit log earns its keep

I'd initially treated the `audit_logs` table as a nice-to-have — debugging telemetry. While writing the chaos tests, I realized it's load-bearing. When a test injects a crash at "between PSP success and tx3 commit" and the retry path completes, the only way to be certain the customer wasn't charged twice is to look at the audit log and read off the sequence of `(action, from_state, to_state, attempt_no)` rows. The `attempt_no` column tells you that the second attempt re-ran phase 3 — exactly what you want — and the `metadata.derived_key` proves the second attempt used the same derived key, so the PSP returned the same `charge_id` from its cache.

If I'd shipped this without the audit log, an on-call investigating a "did we double-charge?" ticket at 3am would have nothing to work with except `recovery_point` and a vague tail of timestamps. With the audit log, the answer falls out in one query. The decision to write one audit row per state transition, with `ON CONFLICT DO NOTHING` to dedupe across phase retries, turned out to be one of the highest-leverage design choices in the system.

## What the chaos tests taught me

The integration tests with happy paths and concurrent retries passed almost immediately. The chaos test — the one that injects a failure between the PSP call and the recovery-point commit — failed the first six times I ran it. Each failure surfaced a bug I hadn't seen at code-review time:

- The first time, I'd forgotten to clear `locked_at` in the error path, so the retry kept seeing a fresh lock.
- The second time, my fake PSP was throwing the simulated error *before* it had recorded the charge, so the dedup-on-retry didn't apply. (In real life, the failure that matters is the one that happens *after* the charge is committed at the PSP.)
- The third time, the `ON CONFLICT DO NOTHING` on the staged-jobs table was missing the right unique key, so retries were silently enqueuing duplicate emails.
- The fourth time, I had a transaction propagation set to `REQUIRED` instead of `REQUIRES_NEW`, and the recovery-point commit was getting rolled back along with the phase that followed.

Each of these was a code-review-grade subtle bug, none of which I'd have caught without a test that actively reaches in and yanks the process. The chaos test is the proof that the whole design is correct; everything else is plumbing.

## What I'd change at scale

The design is single-region, single-Postgres-primary. It will hold under tens of thousands of charges per second, which is comfortably over what most real APIs need. Past that, the bottlenecks become predictable: write contention on `idempotency_keys`, audit-log row growth, and the in-process reaper. Sharding on `user_id` and moving expiry to a separate process — both of which Postgres makes straightforward — gets you another order of magnitude. Past that, you need to either accept that idempotency is regionally-pinned (route a `(user_id, key)` to a fixed home region) or migrate the store to something with global serializability. Multi-region two-phase commit between live systems is rarely worth it; I'd take the regional-pinning trade-off and run.

The piece I keep coming back to is the "completer" idea from Brandur's post — a daemon that finds requests stuck in non-finished states and pushes them to completion, even if the client gave up. It's defensive against an entire class of bug-then-client-disappears failures that nothing else in the system handles. I didn't build it for the v1 because the client retry contract covers the well-behaved case; the completer is the backstop for the badly-behaved one. It's the next thing I'd add.

## What I'll remember

The single biggest mental shift I had to make was this: **the idempotency key is not a deduplication primitive. It's a cursor through a state machine.** Once that landed, the design followed. The four phases, the per-phase transactions, the derived key for the downstream call, the audit log, the lock staleness, the body fingerprint — they're all consequences of treating the request as a piece of work that gets durably advanced one phase at a time, where every phase is restartable from the last persisted cursor, and where every external side effect carries its own deduplication identity.

The two-paragraph description of the system Stripe published in 2017 is correct and complete. Implementing it from scratch is a different exercise: it's where you discover all the places your defaults are wrong, your transaction boundaries are sloppy, and your tests are too optimistic. Worth a week.

---

**References.** [Stripe Engineering — Designing robust and predictable APIs with idempotency](https://stripe.com/blog/idempotency); [Brandur — Implementing Stripe-like Idempotency Keys in Postgres](https://brandur.org/idempotency-keys); [DeepWiki — Idempotency and Retry Logic in stripe-node](https://deepwiki.com/stripe/stripe-node/3.5-idempotency-and-retry-logic); Kleppmann, *Designing Data-Intensive Applications*, Ch. 8 & 11.
