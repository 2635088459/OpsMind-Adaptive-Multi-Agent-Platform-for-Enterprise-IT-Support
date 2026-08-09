# Phase 09 — Security, Audit and Operational Hardening Slice

> Domain: Ticket Workflow
>
> Service: `ticket-workflow-service`
>
> Phase: 09
>
> Specs: `SPEC-TW-033` to `SPEC-TW-036`
>
> Prerequisite: Phase 01 to Phase 08 completed and accepted
>
> Document Status: Implementation Plan

## 1. Phase Goal

Phase 09 hardens the baseline Security, Audit, Idempotency, and Observability capabilities that were introduced incrementally by earlier vertical slices.

It is not the first introduction of security, and it is not a rescue phase for business code with no authorization or audit. It closes, tightens, and verifies production-oriented cross-cutting boundaries.

Core goals:

```text
Security Baseline -> Hardened Authorization
Audit Trail -> Sensitive Read Audit
Free-text Input -> Secret Detection
Normal Auth -> Step-up Authentication
```

## 2. Design Boundaries

- Phase 09 introduces no new primary Ticket lifecycle state.
- Phase 09 does not change Phase 01 to Phase 08 state-machine semantics.
- Support Queue authorization can only narrow access, never broaden visibility.
- Sensitive read audit protects read paths and cannot be bypassed by regular timeline/query code.
- Secret detection blocks sensitive text from Ticket messages, reasons, audit payloads, and outbox payloads; it does not rotate external secrets.
- Step-up authentication applies only to high-risk actions and does not replace baseline JWT/OAuth2 checks.
- Telemetry must remain low-cardinality and contain no PII or secrets.
- Rejected paths must be observable without leaking policy details.

## 3. Phase 09 Specs

| Order | SPEC | Name | Responsibility |
|---|---|---|---|
| 1 | `SPEC-TW-033` | Support Queue Authorization | Harden queue-scoped access, filters, and command admission |
| 2 | `SPEC-TW-034` | Sensitive Read Audit | Enforce fail-closed audit for sensitive reads |
| 3 | `SPEC-TW-035` | Secret Detection | Block secret-like free text in messages and reasons |
| 4 | `SPEC-TW-036` | Step-up Authentication | Require step-up proof for high-risk Ticket commands |

## 4. Cross-cutting Coverage

| Capability | Coverage |
|---|---|
| Authorization | Ticket read, queue query, assignment, escalation, approval, admin action |
| Audit | sensitive read, business command, authorization denied, policy decision |
| Secret Detection | create/update message, request-user-input, approval/rejection reason, escalation/cancel/reopen reason |
| Step-up Auth | cancel, close, reopen closed ticket, escalate, auto-approved high-risk policy |
| Observability | metrics, structured logs, trace attributes, alert trigger |

## 5. Events and Audit

Phase 09 usually does not publish new business lifecycle events. It hardens internal audit and telemetry around:

```text
audit.sensitive-read-recorded
audit.authorization-denied-recorded
security.secret-detected
security.step-up-required
security.step-up-verified
```

These can remain internal audit/telemetry records for this service first. If Audit/Security becomes a separate domain later, they can be bridged through outbox or dedicated integration events.

## 6. Exit Criteria

- `SPEC-TW-033` to `SPEC-TW-036` docs, code, contracts, and tests are closed.
- Support Queue scope applies to both query and command paths.
- Sensitive read audit fails closed.
- Secret-like payloads do not enter business tables, audit free text, or outbox payloads.
- High-risk commands without step-up proof are rejected.
- Rejected paths produce low-cardinality metrics and security logs.
- Phase 01 to Phase 08 golden paths remain intact.
