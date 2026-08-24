-- SPEC-PG-021 (10-failure-handling §Degraded Policy Mode: "audit/metric
-- must mark degraded=true"). Backfilled as NOT NULL DEFAULT so the column
-- can be added without a data migration; new rows always supply a real
-- value going forward.
ALTER TABLE governance.policy_decisions
    ADD COLUMN degraded BOOLEAN NOT NULL DEFAULT false;
