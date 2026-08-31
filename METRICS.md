# METRICS

Every row is a claim made in [README.md](README.md), the command that checks it,
and **what actually happened when that command was run**. Nothing here is
estimated, extrapolated, or copied from another machine. If a claim is not in
this table, the README does not make it.

## Measurement environment

Every number below was produced on this machine and nowhere else:

| | |
|---|---|
| Hardware | Apple M2, 8 cores |
| OS | macOS 15.3.2 |
| JDK | OpenJDK 17.0.14 (Gradle toolchain) |
| Gradle | 8.10 |
| Docker | Server 20.10.21 (Docker Desktop) |
| Database | `postgres:15-alpine`, via Testcontainers for tests and docker-compose for the quickstart |
| Date measured | 2026-08-31 |

Benchmark numbers are single-machine, single-JVM, with Postgres in a local
container. They characterise the per-request cost of the state machine on this
hardware. They are **not** a throughput ceiling for a real deployment, where the
network to the database and to the PSP dominates.

---

## Correctness

| Claim | Metric | Command | Artifact | Observed result |
|---|---|---|---|---|
| The test suite passes | tests / failures / errors | `./gradlew clean test` | `build/test-results/test/TEST-*.xml` | **28 tests, 0 failures, 0 errors, 0 skipped.** `BUILD SUCCESSFUL` |
| The suite is not order-dependent or flaky | repeat runs | `./gradlew test --rerun-tasks` ×4 | same | **4 consecutive runs, 28/28 passing each time.** No failures observed |
| The full build succeeds | build outcome | `./gradlew clean build` | `build/libs/idem-engine-0.1.0.jar` | **`BUILD SUCCESSFUL`**, jar produced |
| Each of the 8 enumerated failure points has its own test | test count in `FailureModeTest` | `./gradlew test --tests '*FailureModeTest'` | `TEST-…FailureModeTest.xml` | **9 tests, 0 failures** (F7 is split into F7a / F7b for the two sides of the ambiguity) |
| 100 concurrent copies of one request produce one charge | `psp.uniqueCharges()`, `count(*) FROM rides` | `./gradlew test --tests '*ConcurrencyTest'` | `TEST-…ConcurrencyTest.xml` | **1 ride, 1 unique PSP charge, 1 staged job, 3 `phase_committed` audit rows.** Every one of the 100 threads returned 201 or 409; none errored |
| Schema drift fails the build rather than passing silently | Hibernate schema validation | `./gradlew test` with `ddl-auto: validate` | test logs | Before the fix: **8 of 11 tests failed** with `Schema-validation: wrong column type encountered in column [request_params_hash] … found [bpchar (Types#CHAR)], but expecting [varchar(64) (Types#VARCHAR)]`. After changing the column to `VARCHAR(64)`: **0 validation errors** |
| Flyway applies the schema from scratch | migration outcome | `./gradlew bootRun` against an empty database | app log | **`Successfully applied 1 migration to schema "public", now at version v1 (execution time 00:00.055s)`** |

### Baseline, for comparison

| Metric | Command | Observed result |
|---|---|---|
| Test outcome before any change in this pass | `./gradlew test` at commit `089eb00` | **11 tests, 8 failed, 3 passed.** All 8 failures were the same Spring context load failure caused by the `CHAR(64)` / `varchar(64)` mismatch; only the 3 pure-unit `RequestHashTest` cases ran |
| Test outcome after | `./gradlew clean test` | **28 tests, 0 failed** |

---

## Quickstart

| Claim | Metric | Command | Artifact | Observed result |
|---|---|---|---|---|
| `docker compose up -d` provisions the database the app expects | database / role inside the container | `docker compose up -d` then `docker exec idem-postgres psql -U idem -d idempotency -tAc 'select current_user, current_database()'` | container | **`idem / idempotency`** — matches the defaults in `application.yml` |
| The app starts with no environment variables set | startup outcome | `./gradlew bootRun` | app log | **`Started IdempotencyApplication in 2.597 seconds`**, `Tomcat started on port 8080`, Flyway v1 applied, `ddl-auto: validate` passed |
| `/actuator/health` reports UP | health status | `curl -s localhost:8080/actuator/health` | HTTP response | **`{"status":"UP","groups":["liveness","readiness"]}`** |
| The wire contract behaves as documented | HTTP status codes | `./scripts/smoke-test.sh 1` | script output | **Exit code 0.** 400 (no key) → 201 (first) → 201 identical body (retry) → 422 (same key, different body) → 201 (different key). The retry returned the same `charge_id` as the first attempt |
| The documented quickstart works on a genuinely empty database | every step asserted, exit status | `./scripts/verify-fresh-start.sh` | script output | **Exit code 0, `RESULT: PASSED`.** Volume destroyed first; 0 tables in `public` before startup; Flyway `V1` applied `success = true`; exactly 1 seeded user (`alice@example.com` / `cus_alice`, id 1); `POST /charges` 201, replay 201 byte-identical, mismatched body 422; 1 `idempotency_keys` row at `recovery_point = finished`, `response_code = 201`, `locked_at IS NULL`; 1 ride `charged` with 1 distinct `psp_charge_id`; stack and volume removed on exit |
| The documented metrics are actually exported | metric names and values | `curl -s localhost:8080/actuator/prometheus \| grep '^idem_'` | HTTP response | After the smoke test: **`idem_cache_hits_total 1.0`**, **`idem_body_mismatch_total 1.0`**, **`idem_requests_total{state="new"} 2.0`**, `{state="cache_hit"} 1.0`, `{state="body_mismatch"} 1.0`, plus `idem_lock_wait_duration_seconds` and `idem_external_call_duration_seconds` histograms |

**Why the container publishes on 5433.** A natively installed PostgreSQL owns
`127.0.0.1:5432` on this machine, so while the container was published on 5432
the application connected to *that* server instead and failed with `FATAL: role
"idem" does not exist`. The container itself was correctly provisioned; the host
port was shadowed. Rather than document a per-machine workaround, the compose
file now publishes `${POSTGRES_PORT:-5433}` and `application.yml`'s default
`DB_URL` points at 5433 to match, so the collision cannot happen and the
quickstart needs no environment variables. The `docker compose up -d postgres` →
`./gradlew bootRun` → `./scripts/smoke-test.sh 1` path was re-run on that
default port with nothing exported: the app reported
`{"status":"UP","groups":["liveness","readiness"]}` and the smoke test exited 0.
The individual startup-time and metric values in the table are from the original
measurement run.

---

## Performance

Measured with `./gradlew jmh`. Configuration: 5 warmup iterations, 10 measurement
iterations, 2 forks (**n = 20 per data point**), single-threaded, both background
sweepers parked so they cannot delete rows mid-measurement.

| Claim | Metric | Command | Artifact | Observed result |
|---|---|---|---|---|
| The cached replay path is materially cheaper than a full state-machine pass | mean latency | `./gradlew jmh` | `benchmarks/jmh-results.json` | **cached `1.352 ± 0.045 ms/op`** vs **unique-key `10.865 ± 0.592 ms/op`** — the cached path is **8.0× faster** |
| Throughput of the two paths | ops/ms | `./gradlew jmh` | `benchmarks/jmh-output.txt` | **cached `0.738 ± 0.028 ops/ms`**, **unique-key `0.091 ± 0.005 ops/ms`** |
| `./gradlew jmh` runs to completion | build outcome | `./gradlew jmh` | `benchmarks/` | **`BUILD SUCCESSFUL`.** It did not before this pass: the JMH bytecode generator forks a JVM without `--enable-preview` and failed with `UnsupportedClassVersionError: Preview features are not enabled for … (class file version 61.65535)` |

### On reading these numbers honestly

- The unique-key path does four committed transactions plus a PSP call; the
  cached path does one indexed read. An 8× gap is what the design predicts, and
  8× is what was measured — but a matching prediction is not extra evidence.
- The PSP here is `FakeExternalPaymentClient`, an in-memory map with
  `psp.fake-latency-ms: 0`. A real PSP contributes tens to hundreds of
  milliseconds, which would dominate the unique-key number entirely. **Do not
  read `10.865 ms/op` as a production latency.**
- An earlier run of the same benchmark, before the background `JobDrain` was
  parked, reported `10.792 ± 12.400 ms/op` for the unique-key path — an error
  bar wider than the mean, i.e. not a measurement at all. The sweeper was
  bulk-deleting hundreds of staged jobs during the measurement window. This is
  recorded here because it is the reason the benchmark configuration changed,
  and because a number with that error bar should never have been reported.
- No multi-threaded benchmark was run. The `@Param({"1","10","100"})` that used
  to sit on the benchmark class was dead — both benchmarks hardcode
  `@Threads(1)`, so it ran the identical configuration three times and told you
  nothing about concurrency. It was removed rather than left to imply a
  measurement that never happened. **The README makes no throughput-at-scale
  claim, because none was measured.**

---

## What is not measured

Stated plainly, so the absence is not mistaken for a result:

- **No production or multi-node measurement.** Everything is one JVM against one
  local Postgres container.
- **No network-level chaos.** `infra/toxiproxy.json` and the Toxiproxy service in
  docker-compose are wired up but no test drives them. All failure injection in
  the suite is in-process: PSP hooks, a temporary Postgres trigger, and stopping
  the state machine between phases. That covers the recovery *logic*; it does not
  cover TCP-level pathologies such as half-open connections.
- **No sustained-load or soak measurement**, so nothing is known about index
  bloat, `audit_logs` growth, or reaper behaviour at volume.
- **No measurement against a real PSP.** Nested idempotency is verified against a
  fake that deduplicates on the derived key, which is the property Stripe
  documents — but it is a model of Stripe, not Stripe.
