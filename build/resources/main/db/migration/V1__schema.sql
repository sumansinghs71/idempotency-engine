-- =============================================================================
-- V1__schema.sql — IdemEngine baseline schema
-- =============================================================================
-- Implements the Stripe / Brandur idempotency-key data model in Postgres 15+.
-- Every column is annotated below.
-- Cite: https://brandur.org/idempotency-keys, https://stripe.com/blog/idempotency
-- =============================================================================

-- ----------------------------------------------------------------------------
-- users
-- ----------------------------------------------------------------------------
-- A minimal users table. In a real deployment this is owned by the auth
-- service; here it backs the foreign key from idempotency_keys.user_id.
-- ----------------------------------------------------------------------------
CREATE TABLE users (
    id                  BIGSERIAL    PRIMARY KEY,
    email               VARCHAR(255) NOT NULL UNIQUE,
    psp_customer_id     VARCHAR(50)  NOT NULL UNIQUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ----------------------------------------------------------------------------
-- idempotency_keys — the core relation
-- ----------------------------------------------------------------------------
-- Each row is the durable bookkeeping for one logical client request.
--
--   key                    Client-supplied UUID-shaped string. Bounded at 100
--                          chars to keep the index size predictable.
--   user_id                Authenticated principal. (user_id, key) is the
--                          uniqueness scope so a leaked key cannot be replayed
--                          by another caller.
--   request_method,        Lock the row to a specific endpoint identity. If a
--   request_path           merchant reuses the same key string against two
--                          different endpoints, the second request hashes
--                          differently → 422, but the (method, path) is also
--                          recorded for debugging.
--   request_params_hash    SHA-256 hex of the canonicalized request body. We
--                          compare hashes (not bodies) for the 422 check.
--   request_body           Full JSONB body. Stored for debugging and to allow
--                          a completer process to replay abandoned requests.
--   response_code          HTTP status of the final response. NULL until
--                          recovery_point = 'finished'.
--   response_body          Cached response body (JSONB). NULL until finished.
--   recovery_point         Position in the state-machine DAG. See enum below.
--                          Updated atomically with the local mutations of each
--                          phase (every phase wraps its work + this UPDATE in
--                          a single REQUIRES_NEW transaction).
--   locked_at              Cross-transaction lock primitive. NULL means
--                          unlocked. If NOT NULL and within 90s of now(), a
--                          concurrent attempt returns 409. Stale (>90s) means
--                          the previous holder died; the next attempt
--                          reclaims by stamping a fresh locked_at.
--   attempt_no             Number of times we've acquired the row. Used for
--                          audit-log correlation and metrics.
--   created_at             Bookkeeping.
--   expires_at             24h TTL by default. Past this, the row is ignored
--                          on read (WHERE expires_at > now()) and deleted by
--                          the reaper.
--
-- UNIQUE (user_id, key)    Per-user scoping. Also the lookup index for the
--                          hot path; covered by `SELECT … FOR UPDATE`.
-- idx_expires_at           Drives the reaper's range delete.
-- ----------------------------------------------------------------------------
CREATE TABLE idempotency_keys (
    id                   BIGSERIAL    PRIMARY KEY,
    key                  VARCHAR(100) NOT NULL,
    user_id              BIGINT       NOT NULL
                                      REFERENCES users(id) ON DELETE RESTRICT,
    request_method       VARCHAR(10)  NOT NULL,
    request_path         VARCHAR(255) NOT NULL,
    request_params_hash  CHAR(64)     NOT NULL,
    request_body         JSONB        NOT NULL,
    response_code        INT,
    response_body        JSONB,
    recovery_point       VARCHAR(50)  NOT NULL DEFAULT 'started',
    locked_at            TIMESTAMPTZ,
    attempt_no           INT          NOT NULL DEFAULT 1,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at           TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_user_key                UNIQUE (user_id, key),
    CONSTRAINT chk_recovery_point         CHECK (recovery_point IN
        ('started','customer_validated','external_api_called','finished'))
);

CREATE INDEX idx_idem_expires_at ON idempotency_keys(expires_at);
-- For Reaper-style "WHERE expires_at < now()" deletes; range scan.

-- ----------------------------------------------------------------------------
-- rides — business record (the thing we actually charge the user for)
-- ----------------------------------------------------------------------------
-- Created during the 'customer_validated' phase with stripe_charge_id = NULL,
-- updated during the 'external_api_called' phase once the PSP returns.
--
-- idempotency_key_id     FK back to the key row. ON DELETE SET NULL so the
--                        reaper can purge expired keys without nuking rides.
-- (user_id, idempotency_key_id) UNIQUE prevents two ride rows for the same
--                        request even if a bug somehow ran phase 2 twice.
-- ----------------------------------------------------------------------------
CREATE TABLE rides (
    id                   BIGSERIAL       PRIMARY KEY,
    idempotency_key_id   BIGINT          REFERENCES idempotency_keys(id)
                                          ON DELETE SET NULL,
    user_id              BIGINT          NOT NULL
                                          REFERENCES users(id) ON DELETE RESTRICT,
    amount_cents         BIGINT          NOT NULL CHECK (amount_cents > 0),
    currency             VARCHAR(3)      NOT NULL,
    status               VARCHAR(20)     NOT NULL DEFAULT 'pending'
                                          CHECK (status IN
                                              ('pending','charged','declined','failed')),
    psp_charge_id        VARCHAR(50)     UNIQUE,
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT uq_ride_per_key UNIQUE (user_id, idempotency_key_id)
);

CREATE INDEX idx_rides_idempotency_key_id
    ON rides(idempotency_key_id)
    WHERE idempotency_key_id IS NOT NULL;

-- ----------------------------------------------------------------------------
-- audit_logs — every state transition produces one row
-- ----------------------------------------------------------------------------
-- The on-call's primary diagnostic. If a duplicate charge ever escapes, the
-- audit row sequence proves exactly which phase ran more than once and from
-- which attempt_no. This is the property we got from a multi-state recovery
-- model that a simple `seen` boolean could not provide.
--
-- action examples:
--   'key_created'        — tx1 committed
--   'phase_committed'    — txN committed; from_state/to_state populated
--   'phase_failed'       — phase threw; from_state populated, to_state NULL
--   'cache_hit'          — replay of finished response
--   'body_mismatch'      — 422 path
--   'lock_conflict'      — 409 path (fresh locked_at)
--   'lock_reclaimed'     — stale lock taken over
--   'reaped'             — deletion by Reaper
--
-- (idempotency_key_id, attempt_no, action, to_state) UNIQUE prevents the audit
-- writer from accidentally inserting two identical rows on a retry; the
-- duplicate just becomes ON CONFLICT DO NOTHING.
-- ----------------------------------------------------------------------------
CREATE TABLE audit_logs (
    id                  BIGSERIAL    PRIMARY KEY,
    idempotency_key_id  BIGINT       REFERENCES idempotency_keys(id)
                                      ON DELETE SET NULL,
    user_id             BIGINT       NOT NULL
                                      REFERENCES users(id) ON DELETE RESTRICT,
    action              VARCHAR(50)  NOT NULL,
    from_state          VARCHAR(50),
    to_state            VARCHAR(50),
    attempt_no          INT          NOT NULL DEFAULT 1,
    metadata            JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_audit_event UNIQUE
        (idempotency_key_id, attempt_no, action, to_state)
);

CREATE INDEX idx_audit_user_created
    ON audit_logs(user_id, created_at DESC);
CREATE INDEX idx_audit_key
    ON audit_logs(idempotency_key_id)
    WHERE idempotency_key_id IS NOT NULL;

-- ----------------------------------------------------------------------------
-- staged_jobs — transactionally-staged job drain (Brandur's pattern)
-- ----------------------------------------------------------------------------
-- A job inserted here is committed atomically with the rest of its phase's
-- transaction. The job-drain process polls this table, executes the job
-- (e.g., send an email via Mailgun), and deletes the row.
--
-- (idempotency_key_id, job_name) UNIQUE — so a retry of phase 4 inserts the
-- same row with ON CONFLICT DO NOTHING. The drain processes each row exactly
-- once because the drain's DELETE is the source of truth for "processed".
-- ----------------------------------------------------------------------------
CREATE TABLE staged_jobs (
    id                  BIGSERIAL    PRIMARY KEY,
    idempotency_key_id  BIGINT       REFERENCES idempotency_keys(id)
                                      ON DELETE SET NULL,
    job_name            VARCHAR(50)  NOT NULL,
    job_args            JSONB        NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_staged_job UNIQUE (idempotency_key_id, job_name)
);

CREATE INDEX idx_staged_jobs_created ON staged_jobs(created_at);

-- ----------------------------------------------------------------------------
-- Trigger: keep rides.updated_at honest.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION touch_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_rides_touch BEFORE UPDATE ON rides
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
