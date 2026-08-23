# 06 Policy Approval Governance Phase / Spec Coverage Matrix

## Goal

This matrix confirms that `06-policy-approval-governance` phase/spec decomposition covers all 14 LLD slices and closes collaboration with `02-ticket-workflow`, `03-agent-runtime-orchestration`, `04-memory-knowledge`, and `05-tool-integration-gateway`.

## Phase / Spec Overview

| Phase | Specs | Closure Goal |
|---|---|---|
| 00 Engineering Foundation | `SPEC-PG-001` to `SPEC-PG-003` | Establish service boundaries, schema baseline, outbox/processed-event/audit baseline for policy-approval-governance-service. |
| 01 Policy Model And Decision Engine | `SPEC-PG-004` to `SPEC-PG-008` | Implement Policy/Rule/Version model, decision API, rule evaluator, risk mapping, and constraints output. |
| 02 Approval Lifecycle | `SPEC-PG-009` to `SPEC-PG-013` | Implement ApprovalRequest, grant/deny/cancel/expire, approval decision finality, and event publication. |
| 03 Security Separation Of Duties | `SPEC-PG-014` to `SPEC-PG-017` | Implement RBAC/ABAC, separation of duties, approval authenticity, MFA/step-up marker, and override guard. |
| 04 Policy Admin And Versioning | `SPEC-PG-018` to `SPEC-PG-021` | Implement policy draft/review/publish/deprecate/supersede, immutable versions, and policy cache refresh. |
| 05 Override And Exception Governance | `SPEC-PG-022` to `SPEC-PG-024` | Implement high-risk override, SLA/ticket exception, admin repair approval, scoped revocation. |
| 06 Cross Domain Contracts | `SPEC-PG-025` to `SPEC-PG-028` | Close governance contracts with 05 Tool Gateway, 03 Agent Runtime, 02 Ticket Workflow, and 04 Memory Knowledge. |
| 07 Observability Audit Compliance | `SPEC-PG-029` to `SPEC-PG-031` | Add metrics/logs/traces, governance audit query, audit integrity, and compliance reporting. |
| 08 Failure Recovery Degraded Mode | `SPEC-PG-032` to `SPEC-PG-034` | Implement evaluator failure handling, approval expiry worker, poison decisions, outbox replay, and fail-closed degraded mode. |
| 09 Final Verification Release | `SPEC-PG-035` to `SPEC-PG-036` | Complete cross-domain contract/e2e harness, final coverage audit, release readiness, and residual risk register. |

## LLD Coverage

| LLD Section | Covered Specs |
|---|---|
| 01-domain-model | `SPEC-PG-004`, `SPEC-PG-005`, `SPEC-PG-009`, `SPEC-PG-022` |
| 02-business-invariants | `SPEC-PG-001`, `SPEC-PG-004`, `SPEC-PG-008`, `SPEC-PG-015`, `SPEC-PG-019`, `SPEC-PG-024` |
| 03-state-machine | `SPEC-PG-002`, `SPEC-PG-005`, `SPEC-PG-009`, `SPEC-PG-011`, `SPEC-PG-012`, `SPEC-PG-018`, `SPEC-PG-020`, `SPEC-PG-022` |
| 04-use-cases | `SPEC-PG-007`, `SPEC-PG-022`, `SPEC-PG-023`, `SPEC-PG-027` |
| 05-api-contracts | `SPEC-PG-006`, `SPEC-PG-010`, `SPEC-PG-011`, `SPEC-PG-016`, `SPEC-PG-018`, `SPEC-PG-025`, `SPEC-PG-028`, `SPEC-PG-030` |
| 06-event-contracts | `SPEC-PG-010`, `SPEC-PG-013`, `SPEC-PG-020`, `SPEC-PG-021`, `SPEC-PG-023`, `SPEC-PG-025`, `SPEC-PG-026`, `SPEC-PG-027`, `SPEC-PG-028` |
| 07-data-model | `SPEC-PG-002`, `SPEC-PG-005`, `SPEC-PG-009`, `SPEC-PG-030` |
| 08-transaction-and-outbox | `SPEC-PG-003`, `SPEC-PG-010`, `SPEC-PG-012`, `SPEC-PG-013`, `SPEC-PG-033` |
| 09-concurrency-and-idempotency | `SPEC-PG-003`, `SPEC-PG-006`, `SPEC-PG-008`, `SPEC-PG-011`, `SPEC-PG-034` |
| 10-failure-handling | `SPEC-PG-007`, `SPEC-PG-012`, `SPEC-PG-021`, `SPEC-PG-024`, `SPEC-PG-032`, `SPEC-PG-033`, `SPEC-PG-034` |
| 11-security | `SPEC-PG-014`, `SPEC-PG-015`, `SPEC-PG-016`, `SPEC-PG-017`, `SPEC-PG-018`, `SPEC-PG-022`, `SPEC-PG-024`, `SPEC-PG-028`, `SPEC-PG-031` |
| 12-observability | `SPEC-PG-003`, `SPEC-PG-017`, `SPEC-PG-029`, `SPEC-PG-030`, `SPEC-PG-031`, `SPEC-PG-032` |
| 13-package-and-class-design | `SPEC-PG-001` |
| 14-testing-strategy | `SPEC-PG-025`, `SPEC-PG-026`, `SPEC-PG-027`, `SPEC-PG-028`, `SPEC-PG-035`, `SPEC-PG-036` |

## Closure With 05 Tool Gateway

- `SPEC-PG-006`: provides risk/approval decision API.
- `SPEC-PG-010` / `013`: handles approval request and publishes granted/denied/expired/cancelled.
- `SPEC-PG-025`: freezes 05/06 contracts so high-risk tools cannot bypass approval.

## Closure With 03 Agent Runtime

- `SPEC-PG-026`: supports workflow approval required, automation risk, and override governance.
- 06 does not advance Workflow state; it only publishes governance facts.

## Closure With 02 Ticket Workflow

- `SPEC-PG-023` / `027`: supports SLA exception, closure override, and escalation exception.
- 06 does not mutate Ticket state; it only outputs approval/policy facts.

## Closure With 04 Memory Knowledge

- `SPEC-PG-028`: supports retention, redaction, sensitive retrieval, and memory publication policy decisions.
- 06 does not write memory content or store raw sensitive knowledge.

## Final Completion Standard

By the end of `SPEC-PG-036`, the project must prove:

- all 14 LLD slices for 06 are covered by specs;
- policy decisions are explainable, reproducible, and traceable to policy versions;
- approval lifecycle is idempotent with one final decision;
- separation of duties and override guards cannot be bypassed;
- key governance contracts with 02/03/04/05 are runnable and testable;
- audit, outbox, recovery, degraded mode, and release readiness are complete.
