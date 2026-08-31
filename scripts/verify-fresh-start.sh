#!/usr/bin/env bash
# =============================================================================
# verify-fresh-start.sh — proves the quickstart works on a genuinely empty database
# =============================================================================
# Runs the full documented lifecycle, in order, and fails loudly at the first
# step that does not hold:
#
#   1. destroy any existing Postgres volume        (so nothing passes on leftovers)
#   2. start Postgres                              (empty data directory)
#   3. assert the database really is empty         (no `users`, no Flyway history)
#   4. build the application jar
#   5. start the app under the `dev` profile       -> Flyway migrates, then the
#                                                     afterMigrate callback seeds
#   6. assert the schema was migrated and seeded
#   7. issue a representative charge, its replay, and a body mismatch
#   8. assert the resulting database state
#   9. shut down the app and the stack, and assert the volume is gone
#
# Exits non-zero on any failure. Every assertion prints what it observed.
#
# Usage:   ./scripts/verify-fresh-start.sh
# Env:     POSTGRES_PORT (default 5433), APP_PORT (default 8080)
# =============================================================================
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

POSTGRES_PORT="${POSTGRES_PORT:-5433}"
APP_PORT="${APP_PORT:-8080}"
DB_URL="jdbc:postgresql://localhost:${POSTGRES_PORT}/idempotency"
APP_LOG="$(mktemp -t idem-verify-app.XXXXXX)"
APP_PID=""

export POSTGRES_PORT

step()  { printf '\n\033[1m== %s ==\033[0m\n' "$*"; }
ok()    { printf '  ok    %s\n' "$*"; }
info()  { printf '  ..    %s\n' "$*"; }
fail()  { printf '  FAIL  %s\n' "$*" >&2; exit 1; }

# psql inside the container, tuples-only and unaligned so output is comparable.
psql_q() { docker exec idem-postgres psql -U idem -d idempotency -tAc "$1" 2>/dev/null; }

expect_eq() {
  # expect_eq <label> <expected> <actual>
  if [[ "$2" == "$3" ]]; then ok "$1 = $3"; else fail "$1: expected '$2', got '$3'"; fi
}

cleanup() {
  local code=$?
  printf '\n\033[1m== 9. teardown ==\033[0m\n'
  if [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" 2>/dev/null; then
    info "stopping app (pid $APP_PID)"
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
    ok "app stopped"
  fi
  docker compose down -v --remove-orphans >/dev/null 2>&1 || true
  if docker volume ls --format '{{.Name}}' | grep -q 'idem-pgdata'; then
    printf '  FAIL  postgres volume still present after teardown\n' >&2
    code=1
  else
    ok "stack down, postgres volume removed"
  fi
  if [[ $code -ne 0 ]]; then
    printf '\n\033[1m-- last 40 lines of the application log --\033[0m\n'
    tail -40 "$APP_LOG" 2>/dev/null || true
    printf '\nRESULT: FAILED (exit %s)\n' "$code"
  else
    printf '\nRESULT: PASSED\n'
  fi
  rm -f "$APP_LOG"
  exit "$code"
}
trap cleanup EXIT

# -----------------------------------------------------------------------------
step "0. preflight"
# -----------------------------------------------------------------------------
command -v docker >/dev/null || fail "docker is not on PATH"
docker compose version >/dev/null 2>&1 || fail "docker compose v2 is not available"
docker info >/dev/null 2>&1 || fail "docker daemon is not running"
command -v curl >/dev/null || fail "curl is not on PATH"
ok "docker $(docker version --format '{{.Server.Version}}'), compose $(docker compose version --short)"
ok "postgres host port ${POSTGRES_PORT}, app port ${APP_PORT}"

# -----------------------------------------------------------------------------
step "1. destroy any existing database volume"
# -----------------------------------------------------------------------------
docker compose down -v --remove-orphans >/dev/null 2>&1 || true
if docker volume ls --format '{{.Name}}' | grep -q 'idem-pgdata'; then
  fail "a postgres volume survived 'docker compose down -v'"
fi
ok "no idem-pgdata volume exists"

# -----------------------------------------------------------------------------
step "2. start Postgres on an empty data directory"
# -----------------------------------------------------------------------------
docker compose up -d postgres
for _ in $(seq 1 60); do
  health="$(docker inspect --format '{{.State.Health.Status}}' idem-postgres 2>/dev/null || echo starting)"
  [[ "$health" == "healthy" ]] && break
  sleep 1
done
[[ "$health" == "healthy" ]] || fail "postgres did not become healthy (last status: $health)"
ok "idem-postgres healthy"

# -----------------------------------------------------------------------------
step "3. assert the database is genuinely fresh"
# -----------------------------------------------------------------------------
expect_eq "to_regclass('public.users')"                 "" "$(psql_q "SELECT COALESCE(to_regclass('public.users')::text, '')")"
expect_eq "to_regclass('public.flyway_schema_history')" "" "$(psql_q "SELECT COALESCE(to_regclass('public.flyway_schema_history')::text, '')")"
expect_eq "tables in public schema"                     "0" "$(psql_q "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")"

# -----------------------------------------------------------------------------
step "4. build the application"
# -----------------------------------------------------------------------------
./gradlew --console=plain -q clean bootJar
JAR="$(ls build/libs/*.jar | grep -v -- '-plain\.jar$' | head -1)"
[[ -f "$JAR" ]] || fail "no bootable jar in build/libs"
ok "built $JAR"

# -----------------------------------------------------------------------------
step "5. start the app (Flyway migrates, then the dev seed callback runs)"
# -----------------------------------------------------------------------------
SPRING_PROFILES_ACTIVE=dev DB_URL="$DB_URL" SERVER_PORT="$APP_PORT" \
  java -jar "$JAR" >"$APP_LOG" 2>&1 &
APP_PID=$!
info "app pid $APP_PID, log $APP_LOG"

up=""
for _ in $(seq 1 120); do
  kill -0 "$APP_PID" 2>/dev/null || fail "the application process exited during startup"
  if curl -fsS "http://localhost:${APP_PORT}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
    up=yes; break
  fi
  sleep 1
done
[[ -n "$up" ]] || fail "the application did not report UP on /actuator/health within 120s"
ok "application UP on http://localhost:${APP_PORT}"

# -----------------------------------------------------------------------------
step "6. assert migration ran, then seeding — in that order"
# -----------------------------------------------------------------------------
expect_eq "flyway_schema_history rows applied" "1" "$(psql_q "SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL")"
expect_eq "V1 migration state"                 "1|true" "$(psql_q "SELECT version || '|' || success FROM flyway_schema_history WHERE version='1'")"
expect_eq "users table exists"                 "users" "$(psql_q "SELECT COALESCE(to_regclass('public.users')::text, '')")"
expect_eq "seeded users"                       "1" "$(psql_q "SELECT count(*) FROM users")"
expect_eq "seeded identity"                    "alice@example.com|cus_alice" "$(psql_q "SELECT email || '|' || psp_customer_id FROM users")"
USER_ID="$(psql_q "SELECT id FROM users WHERE email='alice@example.com'")"
ok "demo user id = ${USER_ID}"

# -----------------------------------------------------------------------------
step "7. representative charge request"
# -----------------------------------------------------------------------------
KEY="verify-$(date +%s)-$$"
BODY_A='{"amount":2000,"currency":"usd","customer_id":"cus_alice"}'
BODY_B='{"amount":9999,"currency":"usd","customer_id":"cus_alice"}'

charge() {
  # charge <key> <body> -> prints "<http_code>\n<body>"
  curl -sS -o /dev/stdout -w '\n%{http_code}' \
    -X POST "http://localhost:${APP_PORT}/charges" \
    -H 'Content-Type: application/json' \
    -H "X-User-Id: ${USER_ID}" \
    -H "Idempotency-Key: $1" \
    --data "$2"
}

RESP1="$(charge "$KEY" "$BODY_A")"; CODE1="${RESP1##*$'\n'}"; JSON1="${RESP1%$'\n'*}"
info "POST /charges          -> ${CODE1} ${JSON1}"
expect_eq "first attempt status" "201" "$CODE1"

RESP2="$(charge "$KEY" "$BODY_A")"; CODE2="${RESP2##*$'\n'}"; JSON2="${RESP2%$'\n'*}"
info "POST /charges (replay) -> ${CODE2} ${JSON2}"
expect_eq "replay status" "201" "$CODE2"
[[ "$JSON1" == "$JSON2" ]] && ok "replay is byte-identical" || fail "replay body differs from the original"

RESP3="$(charge "$KEY" "$BODY_B")"; CODE3="${RESP3##*$'\n'}"
info "POST /charges (mismatched body) -> ${CODE3}"
expect_eq "body-mismatch status" "422" "$CODE3"

# -----------------------------------------------------------------------------
step "8. assert the resulting database state"
# -----------------------------------------------------------------------------
expect_eq "idempotency_keys rows" "1"   "$(psql_q "SELECT count(*) FROM idempotency_keys")"
expect_eq "recovery_point"        "finished" "$(psql_q "SELECT recovery_point FROM idempotency_keys")"
expect_eq "response_code"         "201" "$(psql_q "SELECT response_code FROM idempotency_keys")"
expect_eq "lock released"         "t"   "$(psql_q "SELECT locked_at IS NULL FROM idempotency_keys")"
expect_eq "rides"                 "1"   "$(psql_q "SELECT count(*) FROM rides")"
expect_eq "ride charged with a psp id" "charged|true" \
  "$(psql_q "SELECT status || '|' || (psp_charge_id IS NOT NULL) FROM rides")"
expect_eq "distinct psp charge ids" "1" "$(psql_q "SELECT count(DISTINCT psp_charge_id) FROM rides WHERE psp_charge_id IS NOT NULL")"
expect_eq "cached response matches the wire body" "t" \
  "$(psql_q "SELECT (response_body::jsonb = '${JSON1}'::jsonb) FROM idempotency_keys")"

# Teardown, and the final PASSED/FAILED line, are printed by the EXIT trap.
