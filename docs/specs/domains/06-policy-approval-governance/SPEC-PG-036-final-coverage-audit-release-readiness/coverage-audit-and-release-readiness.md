# SPEC-PG-036 — Coverage Audit, Release Checklist, Residual Risk Register

> Domain: 06 Policy Approval Governance · Service: `policy-approval-governance-service`
> Goal: "Complete LLD/phase/spec/implementation coverage audit, release checklist, and residual risk register."
> This document IS the deliverable for that goal — SPEC-PG-036 adds no production code.

## 1. Spec Coverage Audit

All 36 specs (SPEC-PG-001 through SPEC-PG-036) across 9 phases. Status pulled
directly from each spec's own `traceability-entry.yaml`.

| Phase | Specs | Status |
|---|---|---|
| 00 Engineering Foundation | PG-001–003 | implemented |
| 01 Policy Model And Decision Engine | PG-004–008 | implemented |
| 02 Approval Lifecycle | PG-009–013 | implemented |
| 03 Security Separation Of Duties | PG-014–017 | implemented |
| 04 Policy Admin And Versioning | PG-018–021 | implemented |
| 05 Override And Exception Governance | PG-022–024 | implemented |
| 06 Cross Domain Contracts | PG-025–028 | implemented |
| 07 Observability Audit Compliance | PG-029–031 | implemented |
| 08 Failure Recovery Degraded Mode | PG-032–034 | implemented |
| 09 Final Verification Release | PG-035–036 | implemented (this entry closes PG-036) |

**Result: 36/36 specs `implemented`.** No spec in this domain is `planned` or partial.

## 2. LLD Chapter Coverage Audit

Every LLD chapter under `docs/low-level-design/domains/06-policy-approval-governance/`
cross-referenced against every spec's own `lld_mapping`:

| Chapter | Mapped by (non-exhaustive) |
|---|---|
| 01-domain-model | PG-004, 005, 009 |
| 02-business-invariants | PG-001, 004, 008, 014, 015, 019, 024 |
| 03-state-machine | PG-002, 005, 009, 011, 012, 018, 020, 022 |
| 04-use-cases | PG-007, 022, 023, 027 |
| 05-api-contracts | PG-006, 010, 011, 016, 018, 025, 026, 028, 030 |
| 06-event-contracts | PG-010, 013, 020, 021, 023, 025–028 |
| 07-data-model | PG-002, 005, 009, 019, 030 |
| 08-transaction-and-outbox | PG-003, 010, 012, 013, 033 |
| 09-concurrency-and-idempotency | PG-003, 006, 008, 011, 034 |
| 10-failure-handling | PG-007, 012, 021, 024, 032, 033, 034 |
| 11-security | PG-014–018, 022, 024, 028, 031 |
| 12-observability | PG-003, 017, 029, 030, 031, 032 |
| 13-package-and-class-design | PG-001 |
| 14-testing-strategy | PG-025, 035, 036 |

**Result: 14/14 LLD chapters have at least one implementing spec.** No chapter is
unaddressed.

## 3. Release Checklist

- [x] `./mvnw verify` (full unit + Testcontainers Postgres/RabbitMQ integration
      suite) passes clean: **349/349, BUILD SUCCESS** (see §5 for the exact
      breakdown; re-confirmed at the end of this spec's own work with no code
      changes since).
- [x] ArchUnit `LayerDependencyTest` passes **7/7** — domain has no
      Spring/JPA/AMQP dependency, application never reaches into
      infrastructure, no cross-domain service-package dependency.
- [x] All 36 specs' own `traceability-entry.yaml` show `status: implemented`
      (§1).
- [x] All 14 LLD chapters have at least one implementing spec (§2).
- [x] Flyway migrations apply cleanly end to end: **V001 through V026**,
      sequential, no gaps, confirmed by every `mvnw verify` run's own Flyway
      log ("Successfully applied 26 migrations ... now at version v026").
- [x] No secrets or raw sensitive input persisted or returned by any API —
      SPEC-PG-017 (11-security §Sensitive Data), re-confirmed by SPEC-PG-029's
      own audit.
- [x] RBAC is enforced on every mutating admin/approval/policy-publish
      endpoint via `@PreAuthorize` OAuth2 scopes (SPEC-PG-014/016/018);
      ABAC (risk-clearance claim) is enforced on every approval decision
      (SPEC-PG-014).
- [x] Separation of duties is enforced: requester cannot approve their own
      request, the tool-execution worker cannot approve their own execution,
      policy author/reviewer cannot publish their own unreviewed/self-reviewed
      version (SPEC-PG-014/015/018).
- [x] Idempotency protection exists on every command (decision evaluate,
      approval request/grant/deny/cancel/use/revoke — each with its own
      dedicated idempotency key or business-key uniqueness) and every inbound
      event consumer (`processed_events(eventId, consumerName)`,
      SPEC-PG-003/025–028/034).
- [x] The audit trail is hash-chained, tamper-evident, query/report-capable
      (by ticket/source/decision/approval/policy, plus a compliance report
      and retention/archive action), and admin-repairable for the one
      legitimate case (a poison `processed_events` marker) without ever
      allowing a decision or historical audit record itself to be modified
      (SPEC-PG-003/017/030/031/034).
- [x] Recovery/degraded-mode paths exist, are admin-triggerable, and are
      documented: evaluator failure fails closed by default with an opt-in
      low-risk cache fallback, outbox replay, approval expiry, policy-version
      consistency check, and poison-decision/poison-outbox review all run
      through one `RecoveryService#runRecovery` orchestration
      (SPEC-PG-021/024/032/033).
- [x] Cross-domain event contracts are covered both inbound (mapper unit
      test + real RabbitMQ+Postgres consumer IT for all 4 upstream domains)
      and outbound (a dedicated shape test for all 7 real, graduated domain
      events this service publishes) — SPEC-PG-025–028/035.
- [x] An end-to-end approval lifecycle harness exists proving the full chain
      (inbound event → real `ApprovalRequest` → real grant/deny decision →
      outbox → real broker delivery) actually wires together, not just each
      step in isolation — SPEC-PG-035.

**Release checklist: all items pass. No open blocking item.**

## 4. Residual Risk Register

Every deliberate, documented scope boundary or known gap left open across
the whole domain, gathered from every spec's own `traceability-entry.yaml`.
None of these block release — each was a considered decision at the time,
re-confirmed still accurate here — but each is a legitimate candidate for a
**future** spec/phase if the platform's needs grow to require it.

| # | Item | Where decided | Why it's open | Suggested trigger to close |
|---|---|---|---|---|
| 1 | ABAC only enforces the risk-level dimension of 11-security's four named ABAC dimensions (risk, ticket, tenant, resource) | SPEC-PG-014 | `ApprovalRequest` carries no tenant field and no resource-scope model exists anywhere in this service; claiming to enforce dimensions with no modeled attribute would be dishonest | A future spec that adds multi-tenancy or resource-scoped approval to this domain |
| 2 | `isIndependentApprover` is simple identity inequality (`requesterId != approverId`), not the fuller requester/executor/approver relationship model | SPEC-PG-014 | The richer model (tool-execution-worker-approving-own-execution, admin-repair-initiator-approving-own-override) is covered piecemeal by later, more specific checks (SPEC-PG-015's executor check; the generic requester-cannot-approve-own-request check covers the admin-repair case since no distinct "initiator" identity exists) rather than one unified relationship model | Only if a future spec introduces an actor relationship this collapsed model can't express |
| 3 | `policy.changed.v1` was never added as a distinct event | SPEC-PG-020 | 06-event-contracts names no "changed" event beyond published/superseded/archived/deprecated, each of which already has its own real event and audit action | Only if a future spec's own event-contract explicitly names it |
| 4 | Custom per-business-step OTel spans (the 7-step tracing list in 12-observability) were never built | SPEC-PG-029 | No service in this codebase (this one or any sibling) has ever added manual Observation/span code; trace propagation itself is already satisfied by the platform's existing auto-instrumentation; there is no existing test infrastructure to assert on spans | A platform-wide decision to adopt manual span instrumentation, applied consistently across services, not as a one-off here |
| 5 | Append-only enforcement on `governance_audit_records` relies on the application/port layer (no `update`/`delete` method exists) rather than a database-level trigger or constraint | SPEC-PG-031 | 11-security's own wording is "hash chain OR append-only marker" — the hash chain (SPEC-PG-017) already satisfies that sentence; no migration anywhere in this codebase has ever used a Postgres function/trigger | A demonstrated need for defense-in-depth against direct SQL access bypassing the application layer entirely |
| 6 | 10-failure-handling §Recovery step 5 ("restore evaluator cache") has no corresponding code | SPEC-PG-033 | No evaluator cache exists anywhere in this codebase — `RuleEvaluatorPort` is stateless and the effective `PolicyVersion` is always fetched fresh from Postgres on every call, by design (09-concurrency-and-idempotency §Policy Version Race relies on exactly this) — there is nothing to restore | Only if a future spec introduces an actual evaluator-side cache |
| 7 | Routine outbox replay (`POST /admin/recovery:run` and `:dispatch`) is not itself audited as a governance fact — only the poison-repair `:requeue` action is | SPEC-PG-024 | Routine drains were never audited before this spec either; auditing every scheduled/admin-triggered drain call would be new, unasked-for audit volume with no named requirement behind it | If a future compliance requirement explicitly asks for outbox-replay audit trail |
| 8 | A narrow race window exists between "no existing `decisionKey` found" and the `INSERT` for two truly concurrent identical retries | SPEC-PG-005 | Mitigated, not eliminated: the real `uq_policy_decisions_key_hash` database constraint (09-concurrency-and-idempotency, SPEC-PG-003's own mapping) turns the race into a `DataIntegrityViolationException` on the losing request rather than silent data corruption or a duplicate decision | Only if a caller-visible retry-on-conflict contract is explicitly requested |
| 9 | An anonymous/exclusive AMQP queue declared via `amqpAdmin.declareQueue()` is unreliable for test-side draining when real `@RabbitListener` containers are concurrently active on the same connection factory (this RabbitMQ version also outright rejects transient non-exclusive queues) | SPEC-PG-035 (test infrastructure only, not production code) | Worked around by verifying outbound delivery through `outbox_events.status = 'PUBLISHED'` instead of a second consumer queue — equally valid, since that status only flips after a genuine successful broker publish | Informational only; note for whoever writes the next full-lifecycle IT in this or a sibling service |

**No item in this register is a release blocker.** Each reflects a boundary
this domain's own specs and LLD chapters do not currently ask this service to
cross, not a defect in what they do ask for.

## 5. Final Verification Evidence

`./mvnw test` (unit only): **302/302 passing.**

`./mvnw verify` (full suite, Docker/Testcontainers): **349/349 passing, BUILD
SUCCESS** —
302 unit
+ 3 `PolicyEvaluationRequestedConsumerIT`
+ 4 `TicketApprovalRequiredConsumerIT`
+ 3 `ToolApprovalRequiredConsumerIT`
+ 3 `WorkflowApprovalRequiredConsumerIT`
+ 4 `GovernanceOutboxIT`
+ 28 `GovernancePersistenceIT`
+ 2 `ApprovalLifecycleE2EIT`
= 47 integration tests, all passing against real Postgres and RabbitMQ.

ArchUnit `LayerDependencyTest`: **7/7.**

Flyway: **V001 through V026** apply cleanly on every run, no manual
intervention.
