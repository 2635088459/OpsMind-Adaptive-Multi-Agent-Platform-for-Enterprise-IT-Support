# Ticket Workflow Final Coverage Audit

> **2026-09-01 correction**: this audit's own conclusion ("Implementation
> closure: not yet fully complete") was accurate on 2026-08-10 but is now
> **stale**. A live investigation on 2026-09-01 (JDK 21 installed, real
> `./mvnw test` run) found **`BUILD SUCCESS`, 2039 tests, 0 failures, 0
> errors** across all 41 specs, including the ones this audit flagged as
> incomplete or interrupted (`SPEC-TW-031-escalate-ticket`'s "interrupted"
> code was fixed in the very next commit after this audit was written, on
> 2026-08-11 — `TicketEscalateTest`'s 25 tests all pass). Real
> implementation and test classes were confirmed present for every spec
> in phases 09-10 too (security hardening, reconciliation/replay/
> correction/compensation/integrity-repair), which this audit's Known
> Gaps section said "still require implementation contracts, tests, and
> release gates." Per-spec traceability closing that real gap this audit
> correctly identified (traceability closure, not implementation closure)
> now exists: `docs/traceability/02-ticket-workflow/traceability-matrix.yaml`
> (all 41 specs) and each spec's own `traceability-entry.yaml`. This
> document is kept below for historical context — read it as "the real
> state on 2026-08-10," not the current state.
>
> Domain: `02-ticket-workflow`
>
> Audit Date: 2026-08-10
>
> Baseline: `docs/low-level-design/domains/02-ticket-workflow/`
>
> Roadmap: `docs/implementation-plans/domains/02-ticket-workflow/00-implementation-roadmap_EN.md`
>
> Specs: `docs/specs/domains/02-ticket-workflow/SPEC-TW-001` to `SPEC-TW-041`
>
> Status: Design Coverage Review

## 1. Conclusion

Ticket Workflow is substantially closed at the design-coverage level.

The current document set covers:

- all 14 frozen Low-Level Design groups;
- Phase 00 through Phase 10;
- `SPEC-TW-001` through `SPEC-TW-041`;
- the primary Ticket lifecycle;
- query/message/timeline;
- lifecycle and ownership;
- waiting for user;
- approval and policy;
- tool execution results;
- verification and resolution;
- close/reopen/cancel/assign/escalate;
- security, audit, and operational hardening;
- reconciliation, replay, correction, compensation, and integrity repair.

This audit does not claim that implementation is 100% complete. Reasons:

- `SPEC-TW-001` to `SPEC-TW-006` do not use the same 18-file spec template as later specs;
- some LLD APIs were intentionally folded into broader specs and still need explicit traceability annotations;
- `SPEC-TW-031-escalate-ticket` implementation was interrupted and needs cleanup or completion;
- Phase 09/10 are hardening/recovery layers and still require implementation contracts, tests, and release gates.

Final assessment:

```text
Design coverage: substantially complete
Traceability closure: requires final matrix update
Implementation closure: not yet fully complete
Ready to design 03-agent-runtime-orchestration: yes, after Ticket Workflow cleanup checklist
```

## 2. Phase Coverage Matrix

| Phase | Scope | Specs | Coverage | Notes |
|---|---|---|---|---|
| Phase 00 | Engineering Foundation | no business spec | Covered | build, test, config, security baseline, database/messaging baseline |
| Phase 01 | Create Ticket | `SPEC-TW-001` | Covered | Ticket aggregate, initial SLA/resolution cycle, created event |
| Phase 02 | Query and Message | `SPEC-TW-002` to `SPEC-TW-006` | Covered | get/list/message/support queue/timeline |
| Phase 03 | Lifecycle and Ownership | `SPEC-TW-007` to `SPEC-TW-011` | Covered | triage, assign, transition, resolve, close/reopen |
| Phase 04 | Waiting for User | `SPEC-TW-012` to `SPEC-TW-013` | Covered | request user input, reply/resume |
| Phase 05 | Policy and Approval | `SPEC-TW-014` to `SPEC-TW-018` | Covered | approval request/granted/rejected/expired/auto-approved |
| Phase 06 | Tool Execution | `SPEC-TW-019` to `SPEC-TW-021` | Covered | completed/failed/unknown result |
| Phase 07 | Verification and Resolution | `SPEC-TW-022` to `SPEC-TW-025` | Covered | start verification, success/failure, verified resolution |
| Phase 08 | Closure/Reopen/Assignment/Escalation | `SPEC-TW-026` to `SPEC-TW-032` | Covered | confirmed close, auto-close, reopen, cancel, assign, escalate, resume |
| Phase 09 | Security/Audit/Operational Hardening | `SPEC-TW-033` to `SPEC-TW-036` | Covered | queue auth, sensitive-read audit, secret detection, step-up auth |
| Phase 10 | Reconciliation/Chaos/Release Readiness | `SPEC-TW-037` to `SPEC-TW-041` | Covered | reconciliation, replay, correction, compensation, integrity repair |

## 3. LLD Coverage Matrix

| LLD | Main Concern | Covered By | Assessment |
|---|---|---|---|
| `01-domain-model` | Ticket, Message, Resolution Cycle, SLA Cycle, Assignment, Audit, Outbox | `SPEC-TW-001` to `013`, `026` to `032` | Covered |
| `02-business-invariants` | state constraints, one active cycle, no success without verification, no duplicate execution | all business specs, especially `001`, `009`, `014` to `025`, `029` to `032` | Covered |
| `03-state-machine` | Ticket states, legal transitions, exceptional states | `SPEC-TW-007` to `032` | Covered |
| `04-use-cases` | create, query, message, triage, wait, approve, execute, verify, close, recover | `SPEC-TW-001` to `041` | Covered |
| `05-api-contracts` | public/support/internal APIs, headers, error envelope | `SPEC-TW-001` to `041` | Mostly covered |
| `06-event-contracts` | ticket events, consumed events, outbox payloads | `SPEC-TW-001`, `007` to `041` | Covered |
| `07-data-model` | tables, indexes, constraints, history/audit/outbox/idempotency | persistence docs in `SPEC-TW-001` to `041` | Covered |
| `08-transaction-and-outbox` | transactional outbox, consumer transaction, crash windows | `SPEC-TW-001`, `014` to `025`, `037` to `041` | Covered |
| `09-concurrency-and-idempotency` | idempotency key, request hash, version conflict, duplicate event | all command/event specs | Covered |
| `10-failure-handling` | stale, duplicate, unknown, DLQ, reconciliation | `SPEC-TW-019` to `021`, `024`, `037` to `041` | Covered |
| `11-security` | OAuth/JWT, scopes, queue authorization, field visibility, step-up | `SPEC-TW-002`, `005`, `033`, `036` | Covered |
| `12-observability` | logs, metrics, traces, audit records, alerts | `SPEC-TW-001` to `041`, especially `033` to `041` | Covered |
| `13-package-and-class-design` | package boundaries, ports/adapters, services/controllers | Phase 00 plus all implementation specs | Covered by plan; implementation varies |
| `14-testing-strategy` | unit/integration/contract/failure/chaos tests | each spec test plan plus Phase 10 release gate | Covered by plan; execution pending |

## 4. API / Use Case Coverage

### Fully Mapped

- Create Ticket: `SPEC-TW-001`
- Get Ticket: `SPEC-TW-002`
- List Requester Tickets: `SPEC-TW-003`
- Add Ticket Message: `SPEC-TW-004`
- Support Queue Query: `SPEC-TW-005`
- Ticket Timeline: `SPEC-TW-006`
- Triage Ticket: `SPEC-TW-007`
- Assign Ticket: `SPEC-TW-008`, `SPEC-TW-030`
- Transition Ticket Status: `SPEC-TW-009`
- Resolve Ticket: `SPEC-TW-010`, `SPEC-TW-025`
- Close/Reopen: `SPEC-TW-011`, `SPEC-TW-026` to `028`
- Request User Input / Reply: `SPEC-TW-012` to `013`
- Approval lifecycle: `SPEC-TW-014` to `018`
- Tool result lifecycle: `SPEC-TW-019` to `021`
- Verification lifecycle: `SPEC-TW-022` to `025`
- Cancel/Escalate/Resume: `SPEC-TW-029`, `031`, `032`
- Security hardening: `SPEC-TW-033` to `036`
- Recovery/release readiness: `SPEC-TW-037` to `041`

### Needs Explicit Traceability Annotation

The following LLD APIs/use cases are already covered by design, but need explicit entries in the final traceability matrix:

- List Ticket Messages: primarily covered by `SPEC-TW-004` / `SPEC-TW-006`;
- Start Triage: covered by `SPEC-TW-007`;
- Complete Classification: covered by `SPEC-TW-007`;
- Associate Active Workflow: Ticket-side binding is reserved by `SPEC-TW-009` / `SPEC-TW-019` to `021`; full runtime ownership belongs to `03-agent-runtime-orchestration`;
- Get Internal Ticket Context: covered jointly by `SPEC-TW-002` query models and future Agent Runtime integration specs;
- Retry Failed Automation: Ticket-side handling is covered by `SPEC-TW-020` / `SPEC-TW-021` / `SPEC-TW-037` to `041`; active retry orchestration belongs to Agent Runtime;
- SLA Breach Enforcement: Ticket creation/query already include SLA cycle/summary; the full breach engine may become later SLA/Policy/Observability integration.

## 5. Known Gaps / Risks

| Gap | Impact | Recommendation |
|---|---|---|
| `SPEC-TW-001` to `006` do not share the later 18-file format | review experience is inconsistent | optionally backfill to the 18-file template; not a blocker for Agent Runtime |
| traceability matrix does not yet map all `SPEC-TW-001` to `041` entries | cannot formally claim 100% traceability closure | update matrix from LLD/API/Event/Invariant to Phase/Spec |
| `SPEC-TW-031-escalate-ticket` implementation was interrupted | code may contain partial changes | inspect git diff and complete or clean up SPEC-TW-031 before more code work |
| Phase 09/10 are hardening/recovery docs | need real tests for proof | implementation must include security, failure, chaos, and release gate tests |
| Agent Runtime boundaries are reserved on the Ticket side | agent state must not be implemented inside Ticket Workflow | define Workflow Instance, Task, Checkpoint, Pause/Resume in `03-agent-runtime-orchestration` LLD |

## 6. Cleanup Checklist Before 03-agent-runtime-orchestration

- [ ] Confirm whether `SPEC-TW-031-escalate-ticket` code changes should be completed or restored to a stable state.
- [ ] Run the Ticket Workflow core test suite covering create/query/message/lifecycle/approval/tool/verification/closure.
- [ ] Update the traceability matrix for `SPEC-TW-001` to `SPEC-TW-041`.
- [ ] Mark which specs are docs-complete, code-complete, or pending implementation.
- [ ] Clarify boundaries for `Associate Active Workflow`, `Retry Failed Automation`, and `Get Internal Ticket Context` with Agent Runtime.
- [ ] Decide whether to backfill `SPEC-TW-001` to `SPEC-TW-006` into the 18-file template.

## 7. Final Assessment

Ticket Workflow design coverage is sufficient to start the next domain:

```text
Next Domain: 03-agent-runtime-orchestration
Next Step: generate full LLD baseline for Agent Runtime before phase/spec split
```

Recommended minimum cleanup first:

```text
SPEC-TW-031 code cleanup
Traceability matrix update
Core test verification
```

After those items, Ticket Workflow can be marked as:

```text
Design: Closed
Implementation: Partially Closed / Spec-dependent
Ready for Agent Runtime Design: Yes
```
