# 06 Policy Approval Governance Implementation Roadmap

> Domain: Policy Approval Governance
>
> Service: `policy-approval-governance-service`
>
> Document Status: Implementation Roadmap

## 1. Goal

Turn `06-policy-approval-governance` from LLD into implementable phases/specs: provide unified policy decisions, risk classification, approval lifecycle, separation of duties, override guard, governance audit, and compliance evidence for 02/03/04/05.

## 2. Phase Overview

| Phase | Name | Specs | Goal |
|---|---|---|---|
| 00 | Engineering Foundation | `SPEC-PG-001` ～ `SPEC-PG-003` | Establish service boundaries, schema baseline, outbox/processed-event/audit baseline for policy-approval-governance-service. |
| 01 | Policy Model And Decision Engine | `SPEC-PG-004` ～ `SPEC-PG-008` | Implement Policy/Rule/Version model, decision API, rule evaluator, risk mapping, and constraints output. |
| 02 | Approval Lifecycle | `SPEC-PG-009` ～ `SPEC-PG-013` | Implement ApprovalRequest, grant/deny/cancel/expire, approval decision finality, and event publication. |
| 03 | Security Separation Of Duties | `SPEC-PG-014` ～ `SPEC-PG-017` | Implement RBAC/ABAC, separation of duties, approval authenticity, MFA/step-up marker, and override guard. |
| 04 | Policy Admin And Versioning | `SPEC-PG-018` ～ `SPEC-PG-021` | Implement policy draft/review/publish/deprecate/supersede, immutable versions, and policy cache refresh. |
| 05 | Override And Exception Governance | `SPEC-PG-022` ～ `SPEC-PG-024` | Implement high-risk override, SLA/ticket exception, admin repair approval, scoped revocation. |
| 06 | Cross Domain Contracts | `SPEC-PG-025` ～ `SPEC-PG-028` | Close governance contracts with 05 Tool Gateway, 03 Agent Runtime, 02 Ticket Workflow, and 04 Memory Knowledge. |
| 07 | Observability Audit Compliance | `SPEC-PG-029` ～ `SPEC-PG-031` | Add metrics/logs/traces, governance audit query, audit integrity, and compliance reporting. |
| 08 | Failure Recovery Degraded Mode | `SPEC-PG-032` ～ `SPEC-PG-034` | Implement evaluator failure handling, approval expiry worker, poison decisions, outbox replay, and fail-closed degraded mode. |
| 09 | Final Verification Release | `SPEC-PG-035` ～ `SPEC-PG-036` | Complete cross-domain contract/e2e harness, final coverage audit, release readiness, and residual risk register. |

## 3. Closure Principles

- 06 produces governance facts only; it does not execute tools, mutate Ticket state, mutate Workflow state, or write Memory.
- Every decision must persist policy version, input hash, reason codes, and constraints.
- Every final approval decision must validate approver permission, separation of duties, and request linkage.
- Denied, Expired, Cancelled, and Policy Denied must keep distinct semantics.
- Published policy versions are immutable; rule fixes require new versions.
- Every governance state transition writes audit and outbox in the same transaction.
- Every consumed event must use processed-event deduplication.
