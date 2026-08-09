# SPEC-TW-034 — Sensitive Read Audit

## 1. Goal

Enforce audit for sensitive Ticket reads and fail closed when audit persistence fails.

## 2. Scope

Includes:

- policy evaluation/application hook;
- API, application service, domain policy, audit/metric;
- integration with existing Phase 01 to Phase 08 endpoints;
- `audit.sensitive-read-recorded` internal audit/security record.

Excludes:

- new primary Ticket lifecycle state;
- replacing baseline Keycloak/OAuth2 authentication;
- cross-domain data repair.

## 3. Core Rules

- Sensitive details must not be returned when required audit persistence fails.
- Rejected paths must not produce business-success events.
- Audit and telemetry must not contain secrets, tokens, raw credentials, or high-cardinality fields.
- Policy decisions must be testable, traceable, and observable.
