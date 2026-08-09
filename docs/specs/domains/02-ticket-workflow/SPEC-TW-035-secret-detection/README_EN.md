# SPEC-TW-035 — Secret Detection

## 1. Goal

Prevent secret-like content from entering messages, reasons, audit free text, and outbox payloads.

## 2. Scope

Includes:

- policy evaluation/application hook;
- API, application service, domain policy, audit/metric;
- integration with existing Phase 01 to Phase 08 endpoints;
- `security.secret-detected` internal audit/security record.

Excludes:

- new primary Ticket lifecycle state;
- replacing baseline Keycloak/OAuth2 authentication;
- cross-domain data repair.

## 3. Core Rules

- Free text classified as secret-like must be rejected, metered with redaction, and never persisted raw.
- Rejected paths must not produce business-success events.
- Audit and telemetry must not contain secrets, tokens, raw credentials, or high-cardinality fields.
- Policy decisions must be testable, traceable, and observable.
