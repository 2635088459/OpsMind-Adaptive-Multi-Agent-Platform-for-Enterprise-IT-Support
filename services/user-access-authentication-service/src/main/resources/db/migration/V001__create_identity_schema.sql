-- SPEC-UA-002: one shared Postgres instance, one schema per service (see
-- ticket-workflow-service's `ticket` and policy-approval-governance-service's
-- `governance` schemas for the same convention). This service owns `identity`.
CREATE SCHEMA identity;
