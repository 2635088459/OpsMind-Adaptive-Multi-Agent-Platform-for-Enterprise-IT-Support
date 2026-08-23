# 05 Tool Integration Gateway Implementation Roadmap

> Domain: Tool Integration Gateway
>
> Service: `tool-integration-gateway`
>
> Document Status: Implementation Roadmap

## 1. Goal

Turn `05-tool-integration-gateway` from LLD into implementable phases/specs: Agent Runtime can submit tool intent only through Gateway, while Gateway handles capability resolution, policy/approval integration, credential isolation, connector execution, result normalization, outbox publication, idempotent recovery, and audit closure.

## 2. Phase Overview

| Phase | Name | Specs | Goal |
|---|---|---|---|
| 00 | Engineering Foundation | `SPEC-TG-001` ～ `SPEC-TG-003` | Establish service boundaries, schema baseline, outbox/processed-event/audit baseline for tool-integration-gateway. |
| 01 | Request Intake And Registry | `SPEC-TG-004` ～ `SPEC-TG-006` | Implement ToolRequest aggregate, Runtime API, and Connector/Capability registry as the only tool execution entry point. |
| 02 | Policy Approval Mediation | `SPEC-TG-007` ～ `SPEC-TG-009` | Integrate risk decisions, approval linkage, and approval granted/denied events so high-risk tools cannot bypass approval. |
| 03 | Execution Worker And Connectors | `SPEC-TG-010` ～ `SPEC-TG-015` | Implement scheduling, worker claim/lease, connector SDK, credential preparation, operation keys, side-effect guard, result normalization, and tool.completed publication. |
| 04 | Retry Reconciliation Cancellation | `SPEC-TG-016` ～ `SPEC-TG-019` | Implement retry policy, timeout/partial-side-effect reconciliation, cancellation, and connector health states. |
| 05 | Security And Credential Boundary | `SPEC-TG-020` ～ `SPEC-TG-021` | Enforce secret isolation, controlled raw-output access, authorization scopes, and network policy. |
| 06 | Cross Domain Contracts | `SPEC-TG-022` ～ `SPEC-TG-025` | Close contracts with 03 Runtime, 06 Policy/Approval, 04 Memory Knowledge, and 02 Ticket/Workflow. |
| 07 | Observability Audit Admin | `SPEC-TG-026` ～ `SPEC-TG-029` | Add metrics/logs/traces, audit query, outbox poison/admin repair, and connector admin lifecycle. |
| 08 | Recovery Scaling Degraded Mode | `SPEC-TG-030` | Implement crash recovery, backpressure, worker scaling, and degraded execution control. |
| 09 | Final Verification Release | `SPEC-TG-031` ～ `SPEC-TG-032` | Complete e2e/contract harness, final coverage audit, and release readiness. |

## 3. Closure Principles

- Agents must not call Tools directly; Runtime creates Tool Requests and Gateway executes them.
- 05 owns neither Ticket state nor Workflow state; it owns only Tool Request/Execution state.
- Every external side effect must have an operation key, audit record, and recovery strategy.
- High-risk tools must wait for explicit approval from 06 Policy/Approval.
- Credentials must not enter Agent context, Runtime checkpoint, Ticket comment, Memory, event payload, or logs.
- `tool.completed.v1` means tool execution ended; it does not mean ticket resolved or workflow completed.
- Every consumed event must use processed-event deduplication; every published event must go through Gateway outbox.
