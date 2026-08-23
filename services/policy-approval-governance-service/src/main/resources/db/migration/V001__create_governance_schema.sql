-- SPEC-PG-002: one shared Postgres instance, one schema per service
-- (see ticket-workflow-service's `ticket` schema and tool-integration-gateway's
-- `tool` schema for the same convention). This service owns `governance`.
CREATE SCHEMA governance;
