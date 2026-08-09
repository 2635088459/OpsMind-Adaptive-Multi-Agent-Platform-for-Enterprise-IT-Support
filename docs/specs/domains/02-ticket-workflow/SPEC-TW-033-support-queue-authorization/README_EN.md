# SPEC-TW-033 — Support Queue Authorization

## 1. Goal

Harden Support Queue scoped authorization so reads, queue queries, and command admission use one scope policy.

## 2. Scope

Includes:

- policy evaluation/application hook;
- API, application service, domain policy, audit/metric;
- integration with existing Phase 01 to Phase 08 endpoints;
- `audit.authorization-denied-recorded` internal audit/security record.

Excludes:

- new primary Ticket lifecycle state;
- replacing baseline Keycloak/OAuth2 authentication;
- cross-domain data repair.

## 3. Core Rules

- Any queue-scoped actor can only read or mutate Tickets inside their authorized Support Queue scope.
- Rejected paths must not produce business-success events.
- Audit and telemetry must not contain secrets, tokens, raw credentials, or high-cardinality fields.
- Policy decisions must be testable, traceable, and observable.
