-- IF NOT EXISTS (project-level integration verification, 2026-09-01): real
-- bug found live bringing all 7 services up together for the first time --
-- see policy-approval-governance-service's V001 for the full root-cause
-- writeup (Flyway schema-history collision across services sharing the
-- default `public` schema; fixed via `spring.flyway.schemas` in
-- application.yml, which pre-creates this schema before this file runs).
CREATE SCHEMA IF NOT EXISTS ticket;
