-- =============================================================================
-- afterMigrate__seed_dev_fixtures.sql — development fixtures
-- =============================================================================
-- This is a Flyway *callback*, not a versioned migration. Flyway runs it at the
-- end of every successful `migrate()`, which is what fixes the fresh-database
-- ordering problem: the schema is guaranteed to exist before this file is read,
-- so seeding can never race ahead of the migration that creates `users`.
--
-- It is deliberately NOT in classpath:db/migration. It is picked up only when
-- the `dev` profile adds classpath:db/seed to spring.flyway.locations (see
-- application.yml). The default and `test` profiles never see this file, so no
-- deployment and no test run is ever seeded with demo data.
--
-- Seeding is idempotent (ON CONFLICT DO NOTHING), so restarting the app against
-- an existing database re-runs the callback harmlessly.
--
-- The row exists so the quickstart, the smoke test and the Postman collection
-- have a user id to put in X-User-Id. X-User-Id is a development/demo identity
-- shim, not authentication — see README §7 and IdempotencyInterceptor's javadoc.
-- =============================================================================
INSERT INTO users (email, psp_customer_id)
VALUES ('alice@example.com', 'cus_alice')
ON CONFLICT (email) DO NOTHING;
