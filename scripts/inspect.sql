-- =============================================================================
-- IdemEngine — DB inspection queries
-- Run after the smoke test / Postman collection to verify state-machine state.
--
-- Usage:
--   PGPASSWORD=idem psql -U idem -d idempotency -h localhost -f scripts/inspect.sql
-- or interactively:
--   psql ...
--   \i scripts/inspect.sql
-- =============================================================================

\echo '== Recent idempotency_keys =='
SELECT
    id,
    user_id,
    key,
    recovery_point,
    response_code,
    attempt_no,
    locked_at,
    created_at,
    expires_at
FROM idempotency_keys
ORDER BY id DESC
LIMIT 10;

\echo
\echo '== Recent rides =='
SELECT id, idempotency_key_id, user_id, amount_cents, currency, status, psp_charge_id
FROM rides
ORDER BY id DESC
LIMIT 10;

\echo
\echo '== Audit trail (newest first) =='
SELECT
    a.id,
    a.idempotency_key_id AS key_id,
    a.attempt_no AS att,
    a.action,
    a.from_state,
    a.to_state,
    a.metadata,
    a.created_at
FROM audit_logs a
ORDER BY a.id DESC
LIMIT 20;

\echo
\echo '== Sanity invariants =='
-- All finished rows have a non-null response.
SELECT 'finished_without_response' AS check_name, count(*) AS rows
FROM idempotency_keys
WHERE recovery_point = 'finished' AND (response_code IS NULL OR response_body IS NULL);

-- All finished rows are unlocked.
SELECT 'finished_with_lock' AS check_name, count(*) AS rows
FROM idempotency_keys
WHERE recovery_point = 'finished' AND locked_at IS NOT NULL;
