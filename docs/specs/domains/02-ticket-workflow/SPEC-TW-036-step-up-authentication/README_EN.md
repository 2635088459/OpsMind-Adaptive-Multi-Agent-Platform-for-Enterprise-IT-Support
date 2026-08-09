# SPEC-TW-036 — Step-up Authentication

## 1. Goal

Require step-up proof for high-risk Ticket commands so a normal session cannot directly execute risky operations.

## 2. Scope

Includes:

- policy evaluation/application hook;
- API, application service, domain policy, audit/metric;
- integration with existing Phase 01 to Phase 08 endpoints;
- `security.step-up-verified` internal audit/security record.

Excludes:

- new primary Ticket lifecycle state;
- replacing baseline Keycloak/OAuth2 authentication;
- cross-domain data repair.

## 3. Core Rules

- High-risk commands without valid step-up proof must be rejected before business mutation.
- Rejected paths must not produce business-success events.
- Audit and telemetry must not contain secrets, tokens, raw credentials, or high-cardinality fields.
- Policy decisions must be testable, traceable, and observable.
