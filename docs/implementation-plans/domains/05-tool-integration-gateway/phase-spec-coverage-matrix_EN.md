# 05 Tool Integration Gateway Phase / Spec Coverage Matrix

## Goal

This matrix confirms that `05-tool-integration-gateway` phase/spec decomposition covers all 14 LLD slices and closes collaboration with `02-ticket-workflow`, `03-agent-runtime-orchestration`, `04-memory-knowledge`, and `06-policy-approval-governance`.

## Phase / Spec Overview

| Phase | Specs | Closure Goal |
|---|---|---|
| 00 Engineering Foundation | `SPEC-TG-001` to `SPEC-TG-003` | Establish service boundaries, schema baseline, outbox/processed-event/audit baseline for tool-integration-gateway. |
| 01 Request Intake And Registry | `SPEC-TG-004` to `SPEC-TG-006` | Implement ToolRequest aggregate, Runtime API, and Connector/Capability registry as the only tool execution entry point. |
| 02 Policy Approval Mediation | `SPEC-TG-007` to `SPEC-TG-009` | Integrate risk decisions, approval linkage, and approval granted/denied events so high-risk tools cannot bypass approval. |
| 03 Execution Worker And Connectors | `SPEC-TG-010` to `SPEC-TG-015` | Implement scheduling, worker claim/lease, connector SDK, credential preparation, operation keys, side-effect guard, result normalization, and tool.completed publication. |
| 04 Retry Reconciliation Cancellation | `SPEC-TG-016` to `SPEC-TG-019` | Implement retry policy, timeout/partial-side-effect reconciliation, cancellation, and connector health states. |
| 05 Security And Credential Boundary | `SPEC-TG-020` to `SPEC-TG-021` | Enforce secret isolation, controlled raw-output access, authorization scopes, and network policy. |
| 06 Cross Domain Contracts | `SPEC-TG-022` to `SPEC-TG-025` | Close contracts with 03 Runtime, 06 Policy/Approval, 04 Memory Knowledge, and 02 Ticket/Workflow. |
| 07 Observability Audit Admin | `SPEC-TG-026` to `SPEC-TG-029` | Add metrics/logs/traces, audit query, outbox poison/admin repair, and connector admin lifecycle. |
| 08 Recovery Scaling Degraded Mode | `SPEC-TG-030` | Implement crash recovery, backpressure, worker scaling, and degraded execution control. |
| 09 Final Verification Release | `SPEC-TG-031` to `SPEC-TG-032` | Complete e2e/contract harness, final coverage audit, and release readiness. |

## LLD Coverage

| LLD Section | Covered Specs |
|---|---|
| 01-domain-model | `SPEC-TG-004`, `SPEC-TG-006`, `SPEC-TG-012`, `SPEC-TG-014` |
| 02-business-invariants | `SPEC-TG-001`, `SPEC-TG-004`, `SPEC-TG-007`, `SPEC-TG-013`, `SPEC-TG-021`, `SPEC-TG-025` |
| 03-state-machine | `SPEC-TG-002`, `SPEC-TG-004`, `SPEC-TG-008`, `SPEC-TG-010`, `SPEC-TG-017`, `SPEC-TG-019`, `SPEC-TG-029` |
| 04-use-cases | `SPEC-TG-007`, `SPEC-TG-018`, `SPEC-TG-022`, `SPEC-TG-024` |
| 05-api-contracts | `SPEC-TG-005`, `SPEC-TG-006`, `SPEC-TG-014`, `SPEC-TG-018`, `SPEC-TG-020`, `SPEC-TG-022`, `SPEC-TG-029` |
| 06-event-contracts | `SPEC-TG-008`, `SPEC-TG-009`, `SPEC-TG-015`, `SPEC-TG-022`, `SPEC-TG-023`, `SPEC-TG-024`, `SPEC-TG-025` |
| 07-data-model | `SPEC-TG-002`, `SPEC-TG-006`, `SPEC-TG-014`, `SPEC-TG-027` |
| 08-transaction-and-outbox | `SPEC-TG-003`, `SPEC-TG-008`, `SPEC-TG-010`, `SPEC-TG-015`, `SPEC-TG-028` |
| 09-concurrency-and-idempotency | `SPEC-TG-003`, `SPEC-TG-005`, `SPEC-TG-009`, `SPEC-TG-010`, `SPEC-TG-013`, `SPEC-TG-016`, `SPEC-TG-018`, `SPEC-TG-030` |
| 10-failure-handling | `SPEC-TG-016`, `SPEC-TG-017`, `SPEC-TG-019`, `SPEC-TG-028`, `SPEC-TG-030` |
| 11-security | `SPEC-TG-012`, `SPEC-TG-014`, `SPEC-TG-020`, `SPEC-TG-021`, `SPEC-TG-024` |
| 12-observability | `SPEC-TG-003`, `SPEC-TG-019`, `SPEC-TG-026`, `SPEC-TG-027`, `SPEC-TG-030` |
| 13-package-and-class-design | `SPEC-TG-001`, `SPEC-TG-011` |
| 14-testing-strategy | `SPEC-TG-011`, `SPEC-TG-022`, `SPEC-TG-023`, `SPEC-TG-024`, `SPEC-TG-031`, `SPEC-TG-032` |

## Closure With 03 Agent Runtime

- `SPEC-TG-005`: Runtime creates, queries, and cancels Tool Requests.
- `SPEC-TG-015`: Gateway publishes `tool.completed.v1`, and Runtime resumes workflows waiting for tool results idempotently.
- `SPEC-TG-022`: freezes 03/05 API and event contract and forbids direct Agent tool calls.

## Closure With 06 Policy Approval

- `SPEC-TG-007`: integrates policy/risk decision.
- `SPEC-TG-008`: publishes `tool.approval.required.v1` and persists linkage when approval is needed.
- `SPEC-TG-009` / `SPEC-TG-023`: consume granted/denied and verify approval contract.

## Closure With 04 Memory Knowledge

- `SPEC-TG-014`: standardizes and redacts results.
- `SPEC-TG-020`: controls raw-output access and prevents secrets from entering memory.
- `SPEC-TG-024`: tool evidence refs can be consumed by Memory Knowledge.

## Closure With 02 Ticket Workflow

- `SPEC-TG-025`: every execution is traceable to ticket/cycle but never directly modifies Ticket state.
- `SPEC-TG-015`: tool result returns to Runtime first; Ticket Workflow decides business transitions from facts and verification.

## Final Completion Standard

By the end of `SPEC-TG-032`, the project must prove:

- all 14 LLD slices for 05 are covered by specs;
- Agents cannot bypass Gateway and execute tools directly;
- high-risk tools cannot bypass policy/approval;
- connector side effects are idempotent, deduplicated, and reconcilable;
- credentials and raw output do not leak;
- key contracts with 03/04/06/02 are runnable and testable;
- crash recovery, outbox replay, poison handling, and release readiness are complete.
