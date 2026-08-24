# Policy Approval Governance Service

## Service Purpose

`policy-approval-governance-service` (domain 06) is OpsMind's governance
fact producer: policy decisions (allow/deny/require-approval/allow-with-
constraints) and approval lifecycle outcomes (requested/granted/denied/
expired/cancelled). Per INV-PG-001 it performs no business side effects —
it never executes tools, mutates Ticket/Workflow state, or writes Memory
content; downstream domains (05 Tool Gateway, 02 Ticket Workflow, 03 Agent
Runtime) consume its decisions and approval events and act on them
themselves.

## Current Phase

**Phase 00 — Engineering Foundation — complete (SPEC-PG-001/002/003).
Phase 01 — Policy Model And Decision Engine — complete (SPEC-PG-004 through
SPEC-PG-008). Phase 02 — Approval Lifecycle — complete (SPEC-PG-009 through
SPEC-PG-013). Phase 03 — Security Separation Of Duties — complete (SPEC-PG-014
through SPEC-PG-017). Phase 04 — Policy Admin And Versioning — complete
(SPEC-PG-018 through SPEC-PG-021). Phase 05 — Override And Exception
Governance — complete (SPEC-PG-022 through SPEC-PG-024). Phase 06 — Cross
Domain Contracts — complete (SPEC-PG-025 through SPEC-PG-028). Phase 07 —
Observability Audit Compliance — complete (SPEC-PG-029 through SPEC-PG-031).
Phase 08 — Failure Recovery Degraded Mode — complete (SPEC-PG-032 through
SPEC-PG-034). Phase 09 — Final Verification Release — complete
(SPEC-PG-035/036). All 36 specs across all 9 phases of the domain 06
implementation roadmap are now implemented.**

See
[coverage-audit-and-release-readiness.md](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-036-final-coverage-audit-release-readiness/coverage-audit-and-release-readiness.md)
for the full spec/LLD coverage audit, release checklist, and residual risk
register.

SPEC-PG-001 delivered the hexagonal service skeleton — layered package
boundaries, ports/adapters, and the ArchUnit-enforced architecture
constraints that keep INV-PG-001 true. SPEC-PG-002 replaced the in-memory
adapters with real PostgreSQL persistence: 9 Flyway migrations building the
`governance` schema, real JPA entity/mapper/adapter trios behind every port,
and `@Transactional` boundaries on every application-service write path.
SPEC-PG-003 made the outbox and idempotency real: `OutboxDispatchService`
now durably inserts into `outbox_events` in the same transaction as the fact
it describes and drains due rows through a real RabbitMQ publisher (retry
with backoff, dead-letter after 5 attempts); policy decisions distinguish a
same-hash replay from a genuine `decisionKey` conflict; approval grant/deny
takes a real `SELECT ... FOR UPDATE` lock and returns an existing decision
idempotently instead of racing; and Micrometer metrics + structured logging
now cover the fields 12-observability requires. SPEC-PG-004 gave
`PolicyRule.condition` a real structural type (`RuleCondition`:
attribute/operator/value) instead of an opaque `String` — the one remaining
untyped field among condition/effect/risk/constraints. SPEC-PG-005 added
`PolicyDecision#evaluationFailed`, distinguishing a fail-safe "no effective
policy version" outcome from a genuine rule-driven `DENY` — both still
report `effect = DENY`, so a caller reading only `effect` still fails
closed. SPEC-PG-006 closed out the Decision Evaluate API: `PolicyDecisionService#evaluate`
now fails closed (same `evaluationFailed=true`/`DENY` shape, tagged
`ReasonCode.EVALUATOR_UNAVAILABLE`) if the rule evaluator itself throws, not just when no
effective policy version exists; and `GET /api/v1/policy-decisions/{policyDecisionId}` was
added so a caller holding an id (e.g. from the evaluate response) can re-fetch the immutable
snapshot. `EvaluateDecisionRequest` deliberately keeps an explicit `policyId` field rather than
resolving a policy from scope — 05-api-contracts' example omits it, but no scope-resolution
algorithm exists anywhere in the LLD, and this was confirmed with the user rather than guessed.
SPEC-PG-007 replaced `DefaultRuleEvaluatorAdapter`'s placeholder ("first rule always matches")
with a real condition-matching engine (`RuleConditionMatcher`): rules are tried in order, the
first one whose `RuleCondition` list fully matches the request facts wins, and risk level is a
direct pass-through of that rule's own `riskLevel`. Anything the matcher cannot interpret
(unknown attribute, a condition on a field the request left `null`, a non-numeric comparison)
throws uncaught, which `PolicyDecisionService`'s existing SPEC-PG-006 try/catch already turns
into the same fail-safe `DENY`/`evaluationFailed=true` snapshot — satisfying
10-failure-handling's "rule parsing failure -> EVALUATION_FAILED, never default allow" without a
second fail-closed code path.

SPEC-PG-008 closed out phase-01 by adding test coverage for two invariants that were already
true in production code but unverified: a dedicated test proves a concurrently-published policy
version cannot retroactively change a decision already in flight (09-concurrency-and-idempotency
§Policy Version Race), and another proves a non-empty `constraints` list round-trips correctly
through the real Postgres `jsonb` column (previously only ever exercised with an empty list).
SPEC-PG-009 opened phase-02 by adding `ApprovalRequest#policyDecisionId`, a nullable back-reference
to the `PolicyDecision` an approval request originated from — the one gap `PolicyDecision`'s own
javadoc had named by spec number since SPEC-PG-005 (01-domain-model §Aggregate Boundary: the two
aggregates "may be linked, but are not strictly one-to-one"). Threaded through the domain model,
a real Postgres column with a genuine foreign key (migration V012 — `policy_decisions` lives in
this same schema, unlike `ticket_id`/`workflow_instance_id`), and end-to-end through
`RequestApprovalCommand`/`RequestApprovalRequest`/`ApprovalRequestResponse` so it's usable by a
real caller, not a dead field. SPEC-PG-010 replaced the generic outbox placeholder every governance
action used with the real `approval.requested.v1` event (`ApprovalRequestedEvent`): every published
event's `outbox_events` row now carries the real aggregate's own `aggregateType`/`aggregateId`
(previously hardcoded to a generic `"Governance"` self-reference), and the AMQP payload is the full
06-event-contracts envelope (`producer`/`schemaVersion`/`aggregateId`/`ticketId`/`payload`), not just
the minimal `eventType`/`correlationId` shape. `GET /api/v1/approval-requests/{id}` was also added
(05-api-contracts, deliberately deferred from SPEC-PG-009). Other governance actions
(`policy.decision.created.v1`, `policy.published.v1`, and the SPEC-PG-013-owned approval decision
events) still use the honest empty-payload placeholder until their own owning spec graduates them.
SPEC-PG-011 added `ApprovalDecision#commandIdempotencyKey` (09-concurrency-and-idempotency's own
distinct "approval command" idempotency key, required on every grant/deny request) — `:grant`/
`:deny` retries are now recognized as the same attempt by a strict three-way match
(`commandIdempotencyKey`+`decision`+`decidedBy`), not just outcome/actor, so a different command
that happens to share an outcome and actor is correctly rejected as a conflict instead of being
silently treated as a replay.

SPEC-PG-012 closed out the two gaps its own predecessor named as out of scope: cancel had no
idempotency guard at all (`ApprovalService#cancel` threw `IllegalApprovalTransitionException` on
any retry) and the expiry worker had no invocation surface. `ApprovalRequest#cancelCommandIdempotencyKey`
(new migration V014 column, nullable — only ever set once a request is `CANCELLED`) gives cancel
its own idempotency key, distinct from the grant/deny `commandIdempotencyKey` on
`ApprovalDecision` since cancel never creates a decision row: a retry with the same key now
returns the existing `CANCELLED` state instead of throwing, a different key against an
already-cancelled request is a real conflict (audited as the new `APPROVAL_CANCEL_CONFLICT`
action, migration V015, and rejected with `ApprovalAlreadyCancelledException`), and
`CancelApprovalRequest`/`CancelApprovalCommand` now carry `sourceRequestId`/`requestHash` so
INV-PG-005 request-linkage validation applies to cancel the same way it already does to
grant/deny. `cancel()` and `expireDue()` both now stage their own real, versioned events
(`ApprovalCancelledEvent`/`ApprovalExpiredEvent` — `approval.cancelled.v1`/`approval.expired.v1`)
instead of the generic outbox placeholder, mirroring how SPEC-PG-010 graduated
`approval.requested.v1`. `ApprovalExpiryService#expireDue()` isolates a single row's save failure
(e.g. a concurrent grant/deny racing the scan) so it no longer fails the rest of the batch —
mirroring `OutboxDispatchService#publishPending`'s own established per-item catch-and-continue
pattern rather than a literal per-row transaction. The expiry worker itself gets a real invocation
surface, `POST /api/v1/approval-requests:expire-due` (`ApprovalExpiryController`), following the
exact same "admin endpoint or external scheduler, never a `@Scheduled` trigger" convention
`OutboxDispatchService`'s own javadoc already established for this codebase — an external
scheduler (e.g. a Kubernetes CronJob) is expected to call it on a fixed cadence.

SPEC-PG-013 closed out phase-02 by graduating the last two approval-lifecycle events off the
generic outbox placeholder: `ApprovalService#decide()` now stages the real, versioned
`ApprovalGrantedEvent`/`ApprovalDeniedEvent` (`approval.granted.v1`/`approval.denied.v1`) instead
of `governance.audit.approval_granted.v1`/`governance.audit.approval_denied.v1`, mirroring how
SPEC-PG-010 graduated `approval.requested.v1` and SPEC-PG-012 graduated
`approval.expired.v1`/`approval.cancelled.v1`. Both carry the same
approvalRequestId/sourceDomain/sourceRequestId/requestHash 06-event-contracts §Idempotency
requires on every approval decision event, plus the `ApprovalDecision`'s own `decidedBy`/`reason`/
`conditions`; `approval.granted.v1` additionally carries `separationOfDutiesCheck` (INV-PG-004) —
`approval.denied.v1` omits it since `ApprovalDecision`'s own constructor only ever sets that flag
meaningfully for an `APPROVED` outcome. No schema, API, or state-machine changes were needed —
SPEC-PG-013's own lld_mapping (`06-event-contracts, 08-transaction-and-outbox`) is narrower than
SPEC-PG-011/012's, and the grant/deny idempotency/locking/conflict logic those specs already built
was untouched. `domain.shared.SimpleGovernanceEvent` now only backs the two policy-side events
(`policy.decision.created.v1`/`policy.published.v1`) that still have no owning spec — every
approval-lifecycle event has graduated to a real type.

SPEC-PG-014 opened phase-03 by retiring `StubIdentityAuthorizationAdapter` — the fail-closed
placeholder that denied every approval unconditionally — for a real RBAC + ABAC implementation,
`JwtIdentityAuthorizationAdapter`. RBAC: an approver's OAuth2 JWT must carry the
`SCOPE_approval:decide` authority (Spring's default `JwtAuthenticationConverter`, already wired by
`SecurityConfig`, maps a `scope`/`scp` claim entry to a `SCOPE_*` `GrantedAuthority` — the same
convention ticket-workflow-service already uses for `SCOPE_tickets:create`). ABAC: the token's
`risk_clearance` claim (a list of `RiskLevel` names) must include a level at or above the specific
request's own `riskLevel`, using `RiskLevel`'s own intentionally-ordered `compareTo`.
`IdentityAuthorizationPort#isAuthorizedApprover` gained a `riskLevel` parameter to carry this
through; `isIndependentApprover` also got a real (if intentionally basic — simple identity
inequality) implementation, deferring the fuller requester/executor/approver relationship model to
SPEC-PG-015 (Separation Of Duties Check) by name. Two more RBAC gates were added directly as
`@PreAuthorize("hasAuthority(...)")` on the controller methods that needed no request-specific
ABAC context: `PolicyAdminController#publish` now requires `SCOPE_policy:publish`, and
`GovernanceAuditController#findByCorrelationId` now requires `SCOPE_governance:audit:read` — both
named explicitly by 11-security's own Permission Model wording ("RBAC decides whether a user can
approve, publish policy, or view audit"). Per-ticket/tenant/resource ABAC (11-security's other
three named dimensions) remains deferred — no attribute for any of them is modeled anywhere in
this service yet (`ApprovalRequest` carries no tenant, for instance), so claiming to enforce them
now would be dishonest; only the risk-level dimension, which the domain model already supports, is
real.

SPEC-PG-015 closed out 11-security's own §Separation Of Duties list of forbidden defaults that
were still open: "requester approving their own request" and "policy author publishing their own
unreviewed policy" were already correctly enforced since SPEC-PG-001/003 (confirmed, not
re-implemented); "tool execution worker approving the corresponding tool request" had no code path
at all, since nothing tracked who was assigned to execute a tool request. Added
`ApprovalRequest#executorId` (new migration V016 column, nullable — 06 must not fabricate an
executor identity it was never given, mirroring how `policyDecisionId` stays `null` when a request
never went through a policy evaluation) threaded through `RequestApprovalCommand`/
`RequestApprovalRequest`/`ApprovalRequestResponse`, and a new structural guard in
`ApprovalService#decide` — right beside the existing requester self-approval check — rejecting a
grant/deny whose `decidedBy` matches the request's own `executorId`. "Admin repair initiator
approving the high-risk override directly" (11-security's fourth listed rule) remains
unimplemented: the override feature it refers to does not exist in this codebase yet
(phase-05 — Override And Exception Governance, SPEC-PG-022 onward) — there is no override request
to check separation of duties against.

SPEC-PG-016 gave `domain.approval.ApprovalDecision` "the full signature/session step-up model"
its own javadoc had deferred by name since SPEC-PG-001: `sessionId`/`deviceId`/`stepUpVerified`
(new migration V017 columns on `approval_decisions`), per 11-security §Approval Authenticity
("Approval command must include: authenticated actor; session/device metadata; idempotency key;
reason; optional MFA/step-up marker; correlation id"). `sessionId`/`deviceId` are nullable and
recorded verbatim for the audit trail — 06 has no session store of its own (the platform's
OAuth2/JWT setup is stateless), so it captures what an upstream identity provider reports rather
than independently validating a live session. `stepUpVerified` defaults `false`; `ApprovalService#decide`
structurally requires it for a `CRITICAL`-risk grant (denials never need it — denying withholds
authority rather than granting it) — the narrowest reading of "optional MFA/step-up marker" that
makes it a real security gate rather than inert data collection, and a natural pairing with
SPEC-PG-014's own risk-level ABAC.

SPEC-PG-017 closed the one real gap 11-security §Tamper-Resistant Audit named that wasn't already
true by construction: `SimpleAuditIntegrityAdapter` computed a real per-record SHA-256 fingerprint
since SPEC-PG-003, but nothing chained one record to the next, so altering or deleting an older
record wouldn't be detectable — only that one record's own hash would look wrong. Added
`GovernanceAuditRecord#previousHash` (new migration V018 column, nullable — `null` only for the
very first record this service ever appends) and `GovernanceAuditRepository#findMostRecentIntegrityHash`;
`GovernanceAuditService#record` now looks up the chain's current tail before building each new
record, and `SimpleAuditIntegrityAdapter` folds `previousHash` into the hash itself so a broken
link is detectable, not just a mismatched field. This is a best-effort chain ordered by
`recordedAt`, not a strictly serialized one — see `GovernanceAuditRepository#findMostRecentIntegrityHash`'s
own javadoc for exactly what stronger guarantee (a DB sequence plus a locked chain-head row,
serializing every governance write in the service through one lock) this deliberately does not
claim. The rest of 11-security §Sensitive Data / §Tamper-Resistant Audit was already true without
new code: no controller, log statement, or response DTO in this service has ever carried raw
input — `PolicyDecisionResponse`/`GovernanceAuditRecordResponse` only ever exposed `inputHash`/
`integrityHash`, and every structured log call already carries only the 12-observability field
list (correlationId, approvalRequestId, riskLevel, etc.), confirmed by re-reading every `.log(...)`
call site in this service, not just assumed — and there is no audit-record delete endpoint for
"ordinary admins cannot delete audit records" to guard against.

SPEC-PG-018 and SPEC-PG-019 were done together: both touch the same `PolicyAdminService`/
`PolicyVersion` surface and neither could be tested in isolation from the other's own fixtures.
SPEC-PG-018 (goal: "reviewer/publisher separation of duties") extended `PolicyAdminController`'s
`@PreAuthorize` RBAC gates — which SPEC-PG-014 had only added to `:publish` — to `:draft`
(`SCOPE_policy:draft`) and `:review` (`SCOPE_policy:review`) too, and extended
`PolicyAdminService#publish`'s existing author-cannot-publish-their-own-version guard to the
reviewer as well: `PolicyPublishSeparationOfDutiesException` now names which role conflicted
(`author` or `reviewer`) rather than assuming it was always the author. A version's own reviewer
publishing it is not a real second pair of eyes, the same reasoning the author guard already used.
SPEC-PG-019 (goal: "rule fixes require new versions") closed the one real gap behind `PolicyVersion`'s
already-complete state machine and `withRules`/`PolicyVersionImmutableException` immutability guard
(both built since SPEC-PG-001, already covered by `PolicyVersionTest`): `PolicyAdminService#draft`
had hardcoded every new policy version to `1`, so a policy that already had a `PUBLISHED` version
had no way to get a second one — the very case INV-PG-006 exists for. Added
`PolicyVersionRepository#findLatestVersion` (the highest version number a policy has, regardless of
that version's own status); `draft` now computes the next version as `latest + 1`, falling back to
`1` only for a policy with no version yet. No API or schema change was needed —
`uq_policy_versions_policy_version` was already enforcing "never reuse a version number" at the
database level since SPEC-PG-002; `draft`/`:draft` already accepted a `policyId` for an existing
policy and just mis-numbered the result. Deliberately NOT touched: whether drafting a new version
while an older one is still `DRAFT`/`REVIEWING` should be restricted to one at a time — nothing in
02-business-invariants or this spec's own acceptance criteria names that constraint, so allowing
concurrent in-flight drafts is the honest default rather than an invented restriction; and whether
publishing a new version should automatically supersede the previous `PUBLISHED` one (`PUBLISHED ->
SUPERSEDED` is a legal 03-state-machine transition) — that lifecycle decision belongs to SPEC-PG-020
(Policy Deprecate Supersede Archive) by name, not this one.

SPEC-PG-020 and SPEC-PG-021 were done together: both close out Phase 04 and both extend
`PolicyAdminService`/`PolicyDecisionService`, the same two application services SPEC-PG-018/019
already touched. SPEC-PG-020 (goal: "deprecate/supersede/archive states, policy.published/changed
events") closed two real gaps behind `PolicyVersion`'s already-complete 03-state-machine
(`DEPRECATED -> ARCHIVED` and `PUBLISHED -> SUPERSEDED` were both already legal transitions in
`ALLOWED_TRANSITIONS` since SPEC-PG-001, just never driven by any service method or endpoint).
Added `PolicyAdminService#archive` (mirrors the existing `deprecate` method, transitions
`DEPRECATED -> ARCHIVED`) plus a new `POST /api/v1/policy-versions/{id}:archive` endpoint gated by
`SCOPE_policy:archive`, following the exact `@PreAuthorize` pattern SPEC-PG-014/018 established for
every other named admin action. `PolicyAdminService#publish` now auto-supersedes: after a version is
published, if the policy's previously-published version number is still `PUBLISHED` (not already
manually deprecated out of band), that version is transitioned to `SUPERSEDED` in the same
transaction — added `PolicyVersionRepository#findByPolicyIdAndVersionNumber` to look it up. Also
graduated the generic `governance.audit.policy_published.v1` placeholder event to a real
`PolicyPublishedEvent` (`policy.published.v1`), the same graduation pattern SPEC-PG-010/013 already
used for approval events; `policy.changed.v1` was deliberately NOT added — nothing in this spec's own
event-contract names a distinct "changed" event beyond `published`/`superseded`/`archived`, each of
which already has its own `GovernanceAuditRecord.Action` (added `POLICY_SUPERSEDED`,
`POLICY_ARCHIVED` — new migration V019 CHECK constraint) and audit trail entry, so inventing a fourth
generic event would duplicate what the audit log already records. SPEC-PG-021 (goal: "cache refresh
contract, degraded-mode decisions are flagged") closed the one real gap in `PolicyDecisionService`:
callers had no way to tell a decision made against a healthy evaluator from one made under
`failSafeDecision`'s fail-closed fallback (SPEC-PG-008's `EVALUATOR_UNAVAILABLE` path) — both looked
identical in the response and the audit trail. Added `PolicyDecision#degraded` (new migration V020
`boolean NOT NULL DEFAULT false` column) — `false` for every normal `evaluateAgainst` decision, `true`
only when `failSafeDecision` fires specifically for `EVALUATOR_UNAVAILABLE` (not for a plain
`DENY`/no-effective-version fail-close, which is an ordinary policy outcome, not degraded evaluator
health); the audit reason text and structured decision log both surface it. Deliberately NOT built: a
push-based cache-invalidation event or endpoint — this service's `PolicyDecisionService` re-reads
`PolicyVersionRepository` on every `evaluate` call rather than caching versions in memory at all, so
there is no cache to invalidate; "cache refresh contract" in the spec's own goal describes the
contract a *caller* (e.g. an API gateway) should honor when caching this service's responses, not a
cache this service itself owns — inventing an internal cache layer nothing else in the LLD calls for
would be scope creep, not a real gap.

SPEC-PG-022 (goal: "override request/approve/deny/use/revoke lifecycle and
scope/expiry limits") opens Phase 05. `ApprovalType.POLICY_OVERRIDE` and the
`OVERRIDE_APPLIED` audit action already existed as unused plumbing since the
SPEC-PG-002/003/009 schema baseline (a `POLICY_OVERRIDE` value in the
`approval_type` CHECK constraint, an `OVERRIDE_APPLIED` value in the audit
action CHECK constraint, and a `governance_override_total` metric 12-observability
already named but this port's own javadoc deferred: "no override use case
exists yet") — request/approve/deny for a `POLICY_OVERRIDE` request already
worked identically to every other approval type through the existing
`ApprovalService`/`ApprovalRequest` machinery; nothing ever used/revoked one.
03-state-machine draws a second, override-specific continuation past
`APPROVED` (`OVERRIDE_APPROVED -> OVERRIDE_USED`, `OVERRIDE_APPROVED ->
OVERRIDE_REVOKED`), so rather than a brand-new aggregate/table, this spec
extends `ApprovalStatus` with `USED`/`REVOKED` (reachable only from
`APPROVED`, mirroring how every other terminal status here is reachable only
from a single legal predecessor) and adds `ApprovalRequest#use`/`#revoke` —
both reject a non-`POLICY_OVERRIDE` request (`NotAnOverrideRequestException`,
since an ordinary `TOOL_EXECUTION`/`TICKET_ACTION`/`WORKFLOW_ACTION`/`GENERIC`
approval has no such continuation), and `use` additionally rejects a request
already past `expiresAt` (`OverrideExpiredException`) even though it is still
nominally `APPROVED` — the expiry worker only ever scans `REQUESTED` rows, so
an approved-but-unused override sitting past its window cannot be assumed to
have already been caught. `revoke` is deliberately not blocked by a lapsed
`expiresAt`: formally closing out a stale override is still a legitimate
governance action. `ApprovalService#request` now also enforces UC-PG-006's
"valid only within limited scope and time window" for `POLICY_OVERRIDE`
specifically: a non-null `expiresAt` and at least one `constraint` (the
override's own scope) are required at creation
(`InvalidOverrideRequestException` otherwise) — no other approval type gets
this requirement, since nothing in the spec names it for them.
`ApprovalService#use`/`#revoke` mirror `#cancel`'s own established shape
exactly: lock the row, re-validate request linkage (INV-PG-005), treat a
matching `commandIdempotencyKey` against an already-`USED`/`REVOKED` request
as an idempotent replay and a different key as a conflict (new
`OVERRIDE_USE_CONFLICT`/`OVERRIDE_REVOKE_CONFLICT` audit actions, new
`used_command_idempotency_key`/`revoked_command_idempotency_key` columns —
migration V021 — one per command, the same reasoning
`cancel_command_idempotency_key` already established: neither command
creates a new `ApprovalDecision` row). `use` writes the pre-existing
`OVERRIDE_APPLIED` audit action (its first real caller); `revoke` writes a
new `OVERRIDE_REVOKED` action. Independent-approver enforcement for override
needed no new code: `ApprovalDecision`'s own constructor has structurally
required a passed separation-of-duties check for every `APPROVED` decision,
of every approval type, since SPEC-PG-001 — INV-PG-004's "high-risk overrides
require independent approvers" was already true generically by the time this
spec started. No new event was added: 06-event-contracts' own "Published
Events" list names no override-specific event, only the same `approval.*`
events every approval type already publishes, so `use`/`revoke` are audited
(INV-PG-008 names override explicitly) but do not stage a new versioned
event — inventing one nothing in the LLD names would be scope creep. New
tests: `ApprovalRequestTest` gained 8 cases covering both transitions'
happy/illegal/expired paths; `ApprovalServiceTest` gained 13 covering the
scope/expiry request-time validation, idempotent retry, conflict, wrong-type,
and expired-use paths; `ApprovalControllerTest` gained 3 for the new
`:use`/`:revoke` endpoints (previously zero MockMvc coverage, mirroring
SPEC-PG-012's own gap for `:cancel`); `GovernancePersistenceIT` gained 2
round-tripping the new columns and `USED`/`REVOKED` statuses through a real
Postgres instance. 168/168 unit tests pass (./mvnw test, up from 144 after
SPEC-PG-020/021). Full verification (./mvnw verify, Docker available) also
passed: 189/189 total (168 unit + 4 GovernanceOutboxIT + 17
GovernancePersistenceIT Testcontainers Postgres/RabbitMQ integration). BUILD
SUCCESS.

SPEC-PG-023 (goal: "Support 02 Ticket Workflow approvals for SLA exception,
closure override, and escalation exception") is a narrow spec: the heavy
lifting — request/grant/deny/cancel, idempotency, audit, RBAC/ABAC,
separation of duties — was already generic across every `ApprovalType`
since phase-02/03. 06-event-contracts' own `ticket.approval.required.v1`
purpose line names three distinct sub-kinds ("closure override, escalation
exception, or SLA exception"), but `ApprovalType` only had one generic
`TICKET_ACTION` bucket for all of them — no way for audit, metrics, or a
future RBAC rule to tell an SLA-exception approval from an ordinary ticket
action. Added `TICKET_SLA_EXCEPTION`, `TICKET_CLOSURE_OVERRIDE`,
`TICKET_ESCALATION_EXCEPTION` to `ApprovalType` (migration V022 extends the
`approval_type` CHECK constraint) — `TICKET_ACTION` itself is unchanged and
still covers every other ticket-originated approval that is none of these
three named exceptions. All three flow through the exact same
`ApprovalRequest`/`ApprovalService` machinery every other type already
uses; no new endpoint, no new state, no new event — 06-event-contracts names
no distinct state machine or event for them beyond the existing
`approval.*` family, only the classification itself, and nothing in
04-use-cases or 11-security names an expiry/scope requirement for them the
way UC-PG-006 explicitly does for `POLICY_OVERRIDE`, so none was added.
Deliberately NOT built: the RabbitMQ consumer for `ticket.approval.required.v1`
itself — `RabbitConfig`'s own comment has named this event (alongside
`tool.approval.required.v1`/`workflow.approval.required.v1`/
`policy.evaluation.requested.v1`) as wired by "the future specs that build
each concrete request-creation use case" since SPEC-PG-002, and the
Phase 06 "Cross-Domain Contracts" specs (SPEC-PG-025 through SPEC-PG-028,
each titled "Freeze ... contract" for one specific source domain, including
SPEC-PG-027 "Ticket Workflow Governance Contract") are where that wire-level
consumer and its contract test suite belong — every phase-05 spec's own
test-plan already defers "ticket approval shape with 02 Ticket Workflow" to
a separate Contract Tests section, confirming phase-05's job is the internal
governance capability, not the cross-service wire contract. Until 02 Ticket
Workflow calls the existing synchronous `POST /api/v1/approval-requests`
API (or its own RabbitMQ consumer is built), these three types are usable
but unreached from 02's side — the same "supported but not yet exercised by
a real caller" state every other `ApprovalType` value was in immediately
after SPEC-PG-009 first introduced the enum. New tests: `ApprovalServiceTest`
gained 4 cases (one per new type through request/grant/deny, plus confirming
none of them are treated as an override); `GovernancePersistenceIT` gained 1
round-tripping all three through the real CHECK constraint. 172/172 unit
tests pass (./mvnw test, up from 168 after SPEC-PG-022). Full verification
(./mvnw verify, Docker available) also passed: 194/194 total (172 unit + 4
GovernanceOutboxIT + 18 GovernancePersistenceIT Testcontainers
Postgres/RabbitMQ integration). BUILD SUCCESS.

SPEC-PG-024 (goal: "Provide approval entry points for admin actions such as
outbox replay, poison repair, and manual override") closes Phase 05. Maps
onto 10-failure-handling §Recovery's own three concrete, buildable items:
"outbox replay," "poison repair" (via its own "dead-letter state" —
08-transaction-and-outbox), and "manual override." `OutboxDispatchService#publishPending`
has existed since SPEC-PG-003, deliberately never wired to a `@Scheduled`
trigger — its own javadoc names "an admin endpoint or an external
scheduler" as the intended seam, but no such endpoint existed. Added
`OutboxAdminService` (new — see its own javadoc for why it is a separate
class from `OutboxDispatchService`: `GovernanceAuditService` already depends
on `OutboxDispatchService` to stage its own outbox row, so
`OutboxDispatchService` depending back on `GovernanceAuditService` to audit
a requeue would be a circular bean dependency; `OutboxAdminService` sits
above both instead) and `OutboxAdminController` with
`POST /api/v1/admin/outbox:dispatch` ("outbox replay," a thin pass-through
to the existing `publishPending()`) and
`POST /api/v1/admin/outbox/{outboxId}:requeue` ("poison repair": resets a
dead-lettered `FAILED` row to `PENDING` with a fresh attempt budget — attempt
count 0, not a continuation of the exhausted one — rejects a row that is not
currently `FAILED`, since resetting an already-`PUBLISHED` row would risk a
duplicate publish). Added `OutboxEventRepository#findById`/`#requeue` (no
schema change: `requeue` only updates existing `status`/`attempt_count`/
`available_at` columns) and a new `OUTBOX_EVENT_REQUEUED` audit action
(migration V023) — requeue writes an audit record (`sourceDomain="06"`, the
same self-domain convention `PolicyAdminService` already uses for internal
admin actions with no external source domain) since it is exactly the kind
of "admin change" 01-domain-model's own description of `GovernanceAudit`
names; the routine `:dispatch` replay call is not separately audited,
consistent with `publishPending` never having audited individual publish
attempts before this spec either. Neither endpoint carries a `@PreAuthorize`
scope, mirroring `ApprovalExpiryController`'s own established precedent for
this exact category of admin/scheduler-triggered maintenance endpoint
(baseline authenticated actor only).

"Manual override" needed no new code: SPEC-PG-022 already built the full
`POLICY_OVERRIDE` request/approve/deny/use/revoke lifecycle, independent-
approver-gated by `ApprovalDecision`'s own structural check since
SPEC-PG-001 — an admin performing a repair calls the same
`POST /api/v1/approval-requests` any other requester does. 11-security's
own separation-of-duties line for this spec — "forbid ... admin repair
initiator approving the high-risk override directly" — was therefore
already true generically the moment SPEC-PG-001 first required a passed
separation-of-duties check for every `APPROVED` decision (whoever calls the
request-creation endpoint becomes `requestedBy`, and the pre-existing
self-approval guard rejects `requestedBy == decidedBy` for every approval
type, `POLICY_OVERRIDE` included) — confirmed with an explicit test, not
rebuilt. Deliberately not built: repair mechanisms for
10-failure-handling's other three named poison scenarios (evaluator crash
loop, approval-payload/source-linkage mismatch, incompatible policy rule) —
none of them persists a "stuck" row anywhere in the schema the way a
dead-lettered outbox event does (an evaluator crash already fails closed
and returns `EVALUATION_FAILED` without getting stuck; a payload/linkage
mismatch already throws `ApprovalRequestMismatchException` and is never
persisted); inventing a repair mechanism for a stuck state that does not
exist would not be closing a real gap. New tests: `OutboxAdminServiceTest`
(6 cases — dispatch pass-through, requeue happy path, audit-record
assertion, not-found, not-FAILED, already-PUBLISHED); `OutboxAdminControllerTest`
(4 cases, previously zero MockMvc coverage for either new endpoint);
`ApprovalServiceTest` gained the explicit admin-repair-initiator
self-approval confirmation; `GovernancePersistenceIT` gained 2 cases
round-tripping `findById`/`requeue` through a real Postgres instance.
183/183 unit tests pass (./mvnw test, up from 172 after SPEC-PG-023). Full
verification (./mvnw verify, Docker available) also passed: 207/207 total
(183 unit + 4 GovernanceOutboxIT + 20 GovernancePersistenceIT Testcontainers
Postgres/RabbitMQ integration). BUILD SUCCESS.

SPEC-PG-025 (goal: "Freeze 05 risk decision API, tool.approval.required,
approval granted/denied contracts") opens Phase 06 and is the first spec in
this service to build a real inbound event consumer. `approval.granted.v1`/
`approval.denied.v1` and the risk decision API already existed and needed
no change; `tool.approval.required.v1` was the one real gap —
`RabbitConfig`'s own comment had named it, alongside `workflow.approval.required.v1`/
`ticket.approval.required.v1`/`policy.evaluation.requested.v1`, as wired by
"the future specs that build each concrete request-creation use case" since
SPEC-PG-002, and `processed_events` (06-event-contracts §Idempotency: "every
consumer deduplicates by eventId + consumerName") had existed as schema-only
since SPEC-PG-002/003 with zero application-layer wiring — no port, no
adapter, nothing writing to it. Every phase-05 spec's own test-plan had
already deferred "risk/approval event shape with 05 Tool Gateway" to a
separate Contract Tests section, and phase-06 is literally named "Cross
Domain Contracts" — confirming this is where the actual wire-level consumer
belongs, not phase-05.

Added, mirroring ticket-workflow-service's own consumer architecture at a
proportionate scale (a single event type on one queue, not that codebase's
richer multi-event-type dispatcher/retry-interceptor machinery, since
nothing in 06's own LLD asks for one): `RabbitConfig` now also declares the
inbound `policy-approval-governance.tool-approval-events.v1` queue with its
own DLQ (`x-dead-letter-exchange`/`x-dead-letter-routing-key`,
`defaultRequeueRejected(false)` — 10-failure-handling §Poison Decision:
"approval payload does not match source linkage" belongs in a DLQ, not an
infinite requeue loop); `ToolApprovalRequiredEventConsumer` (`@RabbitListener`)
parses the envelope, validates `eventType`, and maps to a
`RequestApprovalCommand` via the new, pure/unit-testable
`ToolApprovalRequiredEventMapper` (`requestKey`/`sourceRequestId`/`toolRequestId`
= `payload.toolRequestId`; `requestHash` = `payload.inputHash`;
`sourceDomain`/`requestedBy` = `envelope.producer`, since no human actor is
named on this event and 06 must not fabricate one; `causationId` =
`envelope.eventId`). `ProcessedEventRepository` (new port) +
`ProcessedEventPersistenceAdapter` give `processed_events` its first real
caller: `markProcessedIfNew` relies on the existing
`uq_processed_events_event_consumer` constraint rather than a
check-then-insert, the same conflict-detection pattern every other unique
constraint in this service already relies on — no new migration was needed,
the table has existed since SPEC-PG-002. New `ConsumedEventDeduplicationService`
is a small, reusable seam every future inbound consumer (phase-06's
remaining SPEC-PG-026/027/028) can share rather than each hand-rolling its
own dedup check. `ToolApprovalRequiredEventHandler` is the use-case: dedup,
then the exact same `ApprovalService#request` every synchronous
`POST /api/v1/approval-requests` caller already uses — a redelivered message
is a silent no-op (06-event-contracts asks for reprocessing not to create a
second governance fact, not for an error signal back to the broker); a
genuinely different event for the same `toolRequestId` still lands safely
on `ApprovalService#request`'s own pre-existing `requestKey` idempotency.

One real bug surfaced and fixed along the way, caught only by the real
Postgres integration test: `ProcessedEventPersistenceAdapter`'s original
`@Transactional` method caught its own `DataIntegrityViolationException`
internally, but a JPA flush failure leaves the transaction that experienced
it unable to commit even once caught (a JPA/Hibernate rule, not a Spring
one) — this surfaced as `UnexpectedRollbackException` on every conflicting
insert. Fixed by giving the actual insert its own `REQUIRES_NEW` transaction
on the repository interface itself
(`SpringDataProcessedEventJpaRepository#insertIsolated`) and moving the
catch to the (now non-transactional) adapter method, so a conflict dooms
only that isolated transaction, never an ambient one.

New tests: `ToolApprovalRequiredEventMapperTest` (6 cases, pure mapping, no
Spring); `ConsumedEventDeduplicationServiceTest` (3 cases);
`ToolApprovalRequiredEventHandlerTest` (3 cases);
`ToolApprovalRequiredEventConsumerTest` (4 cases, JSON parsing/validation,
no Spring or broker); `ToolApprovalRequiredConsumerIT` (3 cases — the first
integration test in this service exercising a real inbound `@RabbitListener`
end to end against Testcontainers RabbitMQ: consume-and-create, redelivery
dedup, and malformed-message dead-lettering); `GovernancePersistenceIT`
gained 2 cases round-tripping `ProcessedEventRepository#markProcessedIfNew`
through a real Postgres instance. 199/199 unit tests pass (./mvnw test, up
from 183 after SPEC-PG-024). Full verification (./mvnw verify, Docker
available) also passed: 228/228 total (199 unit + 3
ToolApprovalRequiredConsumerIT + 4 GovernanceOutboxIT + 22
GovernancePersistenceIT Testcontainers Postgres/RabbitMQ integration). BUILD
SUCCESS.

SPEC-PG-026 (goal: "Freeze 03 workflow approval/override/automation risk
governance contracts") is the second Phase 06 spec, structurally identical
to SPEC-PG-025 — 06-event-contracts §Consumed Events names only this
event's purpose ("03 requests approval for workflow override, resume, or
automation risk"), not a "Key fields" list the way `tool.approval.required.v1`
got one, and no producer implementation exists yet on 03 Agent Runtime's own
side (a Python service with no `workflow.approval.required.v1` publisher
built) — the same "not yet exercised by a real caller" situation SPEC-PG-025
was in for 05 Tool Gateway. `WorkflowApprovalRequiredPayload` is the direct
structural analog of `ToolApprovalRequiredPayload` with `workflowInstanceId`
standing in for `toolRequestId` as the primary business key, since both
events are structurally the same shape (an upstream domain requesting
approval for one risky action it wants to take) and nothing more specific
is named for this one. Deliberately did NOT split `WORKFLOW_ACTION` into
three new `ApprovalType` values the way SPEC-PG-023 split `TICKET_ACTION`
into `TICKET_SLA_EXCEPTION`/`TICKET_CLOSURE_OVERRIDE`/`TICKET_ESCALATION_EXCEPTION`
— that split was justified there because the ticket event's purpose line
and the spec's own goal text named the exact same three items twice,
consistently; here the spec's own goal text ("approval/override/automation
risk") and the event's purpose line ("override, resume, or automation
risk") disagree on the first item, a weaker, inconsistent signal that reads
as loose prose, not a designed taxonomy — inventing a three-way split from
that would be scope creep, not a real gap.

Everything else reused `ConsumedEventDeduplicationService` and the generic
`ConsumedEventEnvelope`/`ConsumedEventSchemaInvalidException` types
SPEC-PG-025 built specifically to be shared by exactly this kind of second
consumer, so this spec's own new surface is small: `RabbitConfig` gained the
`policy-approval-governance.workflow-approval-events.v1` queue + DLQ bound
to `workflow.approval.required.v1`; `WorkflowApprovalRequiredEventMapper`
(pure mapping); `WorkflowApprovalRequiredEventHandler` (dedup, then the same
`ApprovalService#request`); `WorkflowApprovalRequiredEventConsumer`
(`@RabbitListener`). No new port, no new migration, no new bug — the
transactional fix SPEC-PG-025 made to `ProcessedEventPersistenceAdapter`
already covers this consumer's own dedup calls.

New tests, mirroring SPEC-PG-025's own suite one-for-one:
`WorkflowApprovalRequiredEventMapperTest` (6 cases);
`WorkflowApprovalRequiredEventHandlerTest` (3 cases);
`WorkflowApprovalRequiredEventConsumerTest` (4 cases);
`WorkflowApprovalRequiredConsumerIT` (3 cases — real `@RabbitListener` over
Testcontainers RabbitMQ: consume-and-create, redelivery dedup,
malformed-message dead-lettering). 212/212 unit tests pass (./mvnw test, up
from 199 after SPEC-PG-025). Full verification (./mvnw verify, Docker
available) also passed: 244/244 total (212 unit + 3
ToolApprovalRequiredConsumerIT + 3 WorkflowApprovalRequiredConsumerIT + 4
GovernanceOutboxIT + 22 GovernancePersistenceIT Testcontainers
Postgres/RabbitMQ integration). BUILD SUCCESS.

SPEC-PG-027 (goal: "Freeze 02 ticket approval required, SLA exception,
closure override event contracts") is the third Phase 06 spec — the {@code
ticket.approval.required.v1} consumer, mirroring SPEC-PG-025/026's own
shape and reusing every seam they built
(`ConsumedEventDeduplicationService`/`ConsumedEventEnvelope`/`ConsumedEventSchemaInvalidException`).
Unlike workflow's event, this one has a concrete discriminator to build:
06-event-contracts' own purpose line for this event ("02 requests approval
for closure override, escalation exception, or SLA exception") names the
exact three sub-kinds SPEC-PG-023 already gave `ApprovalType` values to
(`TICKET_SLA_EXCEPTION`/`TICKET_CLOSURE_OVERRIDE`/`TICKET_ESCALATION_EXCEPTION`)
but never gave a real caller — this spec is that caller. Added
`TicketApprovalRequiredPayload#exceptionType` (`"SLA_EXCEPTION"`/`"CLOSURE_OVERRIDE"`/`"ESCALATION_EXCEPTION"`,
mapped 1:1 to those three types by
`TicketApprovalRequiredEventMapper#resolveApprovalType`); `null` (an
ordinary ticket action that is none of the three named exceptions) maps to
the pre-existing generic `TICKET_ACTION`, a legitimate case, not an error —
but a non-null value matching none of the three known names IS rejected as
a schema error (the same strictness `riskLevel` parsing already applies),
since a producer sending an unrecognized value is worth surfacing to the
DLQ, not silently miscategorizing. `ticketId` itself is the primary
business key here (no separate "tool request"/"workflow instance"
identifier exists for a ticket-originated approval), with the same
envelope-fallback pattern the other two mappers use. `RabbitConfig` gained
the `policy-approval-governance.ticket-approval-events.v1` queue + DLQ,
bound to `ticket.approval.required.v1`; `TicketApprovalRequiredEventHandler`/`TicketApprovalRequiredEventConsumer`
are otherwise structurally identical to the tool/workflow consumers. No new
port, no new migration, no new bug — SPEC-PG-025's transactional fix
already covers this consumer's own dedup calls too. New tests, mirroring
the established one-for-one pattern: `TicketApprovalRequiredEventMapperTest`
(9 cases — one more than tool/workflow's own 6, covering all three
exceptionType mappings plus the null-fallback and unrecognized-value
rejection); `TicketApprovalRequiredEventHandlerTest` (3 cases);
`TicketApprovalRequiredEventConsumerTest` (4 cases);
`TicketApprovalRequiredConsumerIT` (4 cases — the usual three plus a
dedicated null-exceptionType-creates-TICKET_ACTION case). 228/228 unit
tests pass (./mvnw test, up from 212 after SPEC-PG-026). Full verification
(./mvnw verify, Docker available) also passed: 264/264 total (228 unit + 4
TicketApprovalRequiredConsumerIT + 3 ToolApprovalRequiredConsumerIT + 3
WorkflowApprovalRequiredConsumerIT + 4 GovernanceOutboxIT + 22
GovernancePersistenceIT Testcontainers Postgres/RabbitMQ integration).
BUILD SUCCESS.

SPEC-PG-028 (goal: "Freeze 04 retention, redaction, sensitive retrieval, and
memory publication policy decision shape") closes Phase 06. Structurally
different from SPEC-PG-025/026/027: 06-event-contracts' remaining consumed
event, `policy.evaluation.requested.v1` ("asynchronous policy evaluation
request"), is the async counterpart of the synchronous risk decision API
(UC-PG-001), not another "X requests approval" event — its consumer targets
`PolicyDecisionService#evaluate`, not `ApprovalService#request`.
`PolicyEvaluationRequestedPayload` mirrors `EvaluateDecisionRequest`'s own
synchronous-API body field-for-field (the natural reading of "the same
input, delivered over the bus instead of HTTP"), and
`PolicyEvaluationRequestedEventMapper`/`Handler`/`Consumer` otherwise follow
the exact three-file shape SPEC-PG-025 established, reusing
`ConsumedEventDeduplicationService` and adding the fourth (and, per
06-event-contracts' own consumed-events list, last) queue + DLQ to
`RabbitConfig`.

Also graduated `policy.decision.created.v1` from the generic {@code
governance.audit.decision_evaluated.v1} placeholder to a real, versioned
`PolicyDecisionCreatedEvent` — the last governance fact in this service
still on the placeholder (`approval.*`/`policy.published.v1` were each
graduated by their own earlier specs). This belongs to SPEC-PG-028
specifically because the new async consumer is the reason a real published
event now matters: an asynchronous caller has no HTTP response to read the
decision from, so it needs the real event on the bus to consume the result,
and the goal's own words — "policy decision **shape**" — name exactly the
wire contract this graduation fixes. A duplicate `decisionKey + inputHash`
still stages nothing (08-transaction-and-outbox §Policy Decision: "does not
create a new event"), unchanged.

New tests: `PolicyDecisionCreatedEventTest` (1 case);
`PolicyDecisionServiceTest` gained 2 cases (real-event-staged, duplicate-stages-nothing);
`PolicyEvaluationRequestedEventMapperTest` (6 cases);
`PolicyEvaluationRequestedEventHandlerTest` (3 cases);
`PolicyEvaluationRequestedEventConsumerTest` (4 cases);
`PolicyEvaluationRequestedConsumerIT` (3 cases — real `@RabbitListener` over
Testcontainers RabbitMQ, targeting `policy_decisions` instead of
`approval_requests`). One test-only bug caught and fixed: the new IT's own
`TRUNCATE` didn't include `approval_requests`, which foreign-keys to
`policy_decisions` — Postgres correctly refused to truncate one without the
other. 244/244 unit tests pass (./mvnw test, up from 228 after SPEC-PG-027).
Full verification (./mvnw verify, Docker available) also passed: 283/283
total (244 unit + 3 PolicyEvaluationRequestedConsumerIT + 4
TicketApprovalRequiredConsumerIT + 3 ToolApprovalRequiredConsumerIT + 3
WorkflowApprovalRequiredConsumerIT + 4 GovernanceOutboxIT + 22
GovernancePersistenceIT Testcontainers Postgres/RabbitMQ integration). BUILD
SUCCESS.

SPEC-PG-029 (goal: "Implement governance metrics, structured logs, trace
propagation, and sensitive data filtering") opens Phase 07. An audit against
12-observability's own explicit field/metric lists found three of the four
already fully done and one genuine, bounded gap. Metrics: all 10 named
metrics (`policy_decision_total` through `governance_outbox_pending_count`)
already existed, the last one (`governance_override_total`) added by
SPEC-PG-022 — nothing to add. Sensitive data filtering: already closed by
SPEC-PG-017 (11-security §Sensitive Data) — re-confirmed, not rebuilt. Trace
propagation: already satisfied by the platform's existing OTel
auto-instrumentation (HTTP/JDBC/AMQP boundaries) plus the OTLP
endpoint already configured in `application-local.yml` — "propagation"
means trace context flowing through headers/MDC/AMQP, which the
auto-configured bridge already handles transparently; custom per-business-step
span creation for the 7-step list was deliberately NOT built — no other
service in this codebase has ever added manual Observation/span code, and
building an entirely new, untested pattern for one spec, when "propagation"
does not literally demand named business-step spans, would be scope creep.

The one real gap: `causationId` was silently dropped (hardcoded `null`) on
every `grant`/`deny`/`cancel`/`use`/`revoke` audit record and staged event,
even though `api.support.GovernanceRequestContext#causationId` already read
the `X-Causation-Id` header generically for every endpoint — only
`DecideApprovalCommand`/`CancelApprovalCommand`/`UseOverrideCommand`/`RevokeOverrideCommand`
had no field to carry it (unlike `RequestApprovalCommand`, which already
had one). Added `causationId` to all four command records and threaded it
through `ApprovalController` → `ApprovalService` → every `auditService.record`
call and every `ApprovalGrantedEvent`/`ApprovalDeniedEvent`/`ApprovalCancelledEvent`
`.from(...)` call. Also completed every `ApprovalService` structured log
call with the full 12-observability §Logs field set that is structurally
available at each site (`policyDecisionId`/`ticketId`/`workflowInstanceId`
included even when `null` — a `null` there is itself meaningful: "not
ticket/workflow/decision-scoped"). Gave `PolicyAdminService` its first-ever
structured logging (draft/review/publish/deprecate/archive each now log
`correlationId`/`policyVersion` — this service had zero structured logs
before, unlike every other governance-mutating application service);
`causationId` was deliberately not added there, since nothing in
06-event-contracts names an inbound event these pure HTTP-actor-initiated
admin actions react to. Gave `ApprovalExpiryService#expireDue` a
success-path structured log per expired row — it previously only logged the
failure path.

New assertions (not new test methods, since the fix is additive/observability):
`grantStagesTheRealApprovalGrantedEventWithCorrectAggregateIdentity` and
`cancelStagesTheRealApprovalCancelledEventWithCorrectAggregateIdentity` each
gained a `causationId` propagation check against the staged event's own
envelope. 244/244 unit tests pass (./mvnw test, unchanged from SPEC-PG-028
— this spec fixed and completed existing behavior rather than adding new
surface). Full verification (./mvnw verify, Docker available) also passed:
283/283 total (unchanged tally, same 6 IT classes). BUILD SUCCESS.

SPEC-PG-030 (goal: "Implement governance audit chain queries by
ticket/source/decision/approval/policy"). Before this spec `GovernanceAuditRecord`
carried only one per-action business key, `sourceRequestId`, whose meaning
already varies by `action` (the decision key for `DECISION_EVALUATED`, the
upstream request id for approval actions) — there was no field, and so no
way to query, "every audit record touching this exact ticket/approval
request/policy decision" directly, and `GovernanceAuditController` exposed
only `findByCorrelationId`. Added three new nullable linkage columns to
`GovernanceAuditRecord`/the JPA entity/migration V024
(`ticket_id`/`approval_request_id`/`policy_decision_id`, plus 5 new indexes)
— nullable because only the actions that genuinely carry them set them (a
policy admin action sets none of the three; an approval action sets
`ticketId`/`approvalRequestId` when the request itself carries them); all
three are folded into `SimpleAuditIntegrityAdapter`'s SHA-256 canonical
string alongside the existing fields, since they are genuine facts and
tampering with them must invalidate the hash chain like any other field.
`GovernanceAuditService#record` (both overloads) gained the three as
required trailing parameters, and every one of the 18 existing
`auditService.record(...)` call sites across `ApprovalService` (9),
`PolicyDecisionService` (1), `PolicyAdminService` (6), `ApprovalExpiryService`
(1), and `OutboxAdminService` (1) was updated to pass the linkage ids that
genuinely apply at that call site (`null` for the ones that structurally
cannot, e.g. policy admin actions never touch a ticket/approval/decision id).
Added 5 new query methods to `GovernanceAuditService`/`GovernanceAuditRepository`/
the JPA repository/persistence adapter/mapper — `findByTicketId`,
`findByApprovalRequestId`, `findByPolicyDecisionId`, `findBySourceRequestId`
(the query the old single-purpose `sourceRequestId` field could never answer
across actions), and `findByPolicyId`.

`GovernanceAuditController`'s existing `GET /api/v1/governance-audit-records`
endpoint now accepts 5 additional optional query params
(`ticketId`/`approvalRequestId`/`policyDecisionId`/`sourceRequestId`/`policyId`)
alongside the original `correlationId` — exactly one of the six is required
per call (`RequestValidationException` -> `400` otherwise), since compounding
filters was never named as a requirement and each dimension already answers
a complete, self-contained question. `GovernanceAuditRecordResponse` now
exposes all three new linkage ids. New tests: `GovernanceAuditServiceTest`
gained 5 cases (one per new query method, on top of its existing 2);
`GovernanceAuditControllerSecurityTest` gained 3 (all 5 new query dimensions
reachable through the authorized endpoint, zero-filter rejected, more-than-one-filter
rejected); `GovernancePersistenceIT` gained 1 round-tripping all 5 query
dimensions through the real Postgres columns; `SimpleAuditIntegrityAdapterTest`
gained 1 confirming a changed linkage field changes the computed hash. 253/253
unit tests pass (./mvnw test, up from 244 after SPEC-PG-029). Full
verification (./mvnw verify, Docker available) also passed: 293/293 total
(253 unit + 3 PolicyEvaluationRequestedConsumerIT + 4
TicketApprovalRequiredConsumerIT + 3 ToolApprovalRequiredConsumerIT + 3
WorkflowApprovalRequiredConsumerIT + 4 GovernanceOutboxIT + 23
GovernancePersistenceIT Testcontainers Postgres/RabbitMQ integration). BUILD
SUCCESS. ArchUnit LayerDependencyTest 7/7.

SPEC-PG-031 (goal: "Implement audit hash chain/append-only marker,
compliance reports, and audit retention") closes Phase 07. Of the three
named items, two already existed: the hash chain (SPEC-PG-017), and
"append-only" — 11-security's own wording is "hash chain OR append-only
marker," so the hash chain alone already satisfies that sentence, and
separately, no method on `GovernanceAuditRepository` has ever allowed
updating or deleting a written record's own fact fields (confirmed, not
rebuilt — the same "already closed" pattern SPEC-PG-029 used for sensitive
data filtering). A DB-level trigger enforcing the same thing was considered
and deliberately not built: no other migration in this codebase (in this
service or any other) has ever used a Postgres function/trigger, and the
"OR" in 11-security's own sentence means one is not additionally required
once the hash chain already exists — building an entirely new,
platform-wide-unprecedented infrastructure pattern for one spec would be
scope creep, not closing a real gap.

The two genuine, previously-unbuilt gaps: nothing had ever verified the
hash chain back (it was write-only), and there was no way to retire an old
record without deleting it (11-security §Tamper-Resistant Audit: "Ordinary
admins cannot delete audit records; they may only be archived by retention
policy" — no delete path ever existed, but no archive path did either).
Added `GovernanceAuditService#verifyChain` — walks every record in
`recordedAt` order, recomputes each one's hash via `AuditIntegrityPort`, and
confirms `previousHash` still equals the prior record's own stored hash,
stopping at the first break since everything after an already-broken link
is unverifiable by definition. `#complianceReport` builds on it: total/
active/archived record counts, the covered date range, a per-`Action`
breakdown, and the same chain verification, fetching the record set once
and reusing it for both. `#archiveRecordedBefore(retentionDays, actorId,
reason, correlationId)` is the retention action itself — marks every
record older than the caller-supplied `retentionDays` as archived (no fixed
retention duration is hardcoded anywhere; the caller supplies it each time,
the same "no invented business rule" shape `OutboxAdminService#requeue`
already uses for its own reason) and writes its own `AUDIT_RECORDS_ARCHIVED`
audit entry, but only when at least one record was actually touched —
mirrors `ApprovalExpiryService#expireDue`'s own precedent of staying silent
on a no-op run.

Added a new nullable `archivedAt` field to `GovernanceAuditRecord` (migration
V025) and a new `withArchivedAt` copy method — the one field a retention run
is ever allowed to change on an already-written record. Deliberately
excluded from `SimpleAuditIntegrityAdapter`'s hash computation: archiving is
a retention-policy action on an already-true fact, not a change to the fact
itself, so it must not retroactively look like tampering against a hash
computed before the record was ever archived (new test:
`doesNotChangeWhenArchivedAtChanges`). `GovernanceAuditRepository` gained
`findAllOrderedByRecordedAt` (the full walk order both new report methods
need) and `archiveRecordedBefore(cutoff, archivedAt)` — the one port method
that updates an already-appended row, and it can only ever set that one
column (a bulk JPQL `UPDATE` touching only `archivedAt`, mirroring
`OutboxEventPersistenceAdapter#requeue`'s own `@Transactional`-on-the-adapter
pattern for exactly the same reason). New `GovernanceAuditComplianceController`
(kept separate from `GovernanceAuditController`, mirroring how
`OutboxAdminController` got its own class): `GET
/api/v1/governance-audit/compliance-report` (same `SCOPE_governance:audit:read`
as any other audit view) and `POST /api/v1/admin/governance-audit:archive`
(no `@PreAuthorize` scope, mirroring `OutboxAdminController`'s own precedent
for this category of admin/scheduler-triggered maintenance endpoint).
`GovernanceAuditRecordResponse` now exposes `archivedAt`.

New tests: `GovernanceAuditServiceTest` gained 7 cases (chain intact,
tampered-hash detection, compliance report aggregation on both a populated
and an empty trail, retention cutoff filtering, the audit-entry-on-success
and silent-on-no-op archive behaviors); `SimpleAuditIntegrityAdapterTest`
gained 1; new `GovernanceAuditComplianceControllerSecurityTest` gained 6
(compliance-report scope enforcement, archive's baseline-authenticated-only
requirement, and its `@NotBlank reason` validation); `GovernancePersistenceIT`
gained 1 round-tripping `archived_at` and `archiveRecordedBefore` through a
real bulk `UPDATE` against Postgres, including that a second run over the
same cutoff does not re-touch or re-count an already-archived row. 267/267
unit tests pass (./mvnw test, up from 253 after SPEC-PG-030). Full
verification (./mvnw verify, Docker available) also passed: 308/308 total
(267 unit + 3 PolicyEvaluationRequestedConsumerIT + 4
TicketApprovalRequiredConsumerIT + 3 ToolApprovalRequiredConsumerIT + 3
WorkflowApprovalRequiredConsumerIT + 4 GovernanceOutboxIT + 24
GovernancePersistenceIT Testcontainers Postgres/RabbitMQ integration). BUILD
SUCCESS. ArchUnit LayerDependencyTest 7/7.

SPEC-PG-032 (goal: "Implement evaluator failure, fail closed behavior,
low-risk cache fallback, and degraded metrics") opens Phase 08. An audit
against 10-failure-handling §Degraded Policy Mode's own four clauses found
two already fully built and two genuine gaps. Already done, confirmed not
rebuilt: "evaluator failure" (`PolicyDecisionService#evaluate` has caught
`RuleEvaluatorPort` exceptions since SPEC-PG-001/005) and "fail closed
behavior" (`failSafeDecision` has always returned `DENY`/`HIGH`/
`evaluationFailed=true`, never `ALLOW`, since the same specs).

The two real gaps, both from the one LLD sentence neither prior spec had
fully implemented: "high-risk mutation fails closed; low-risk read-only may
use latest published policy cache." Before this spec, every evaluator
crash failed closed unconditionally — there was no way for 06 to tell a
low-risk read-only request apart from a high-risk mutation before running
the (crashed) evaluator, since risk/read-only-ness is normally an
evaluator *output*, and no input field carried a caller's own
classification. Resolved by adding a caller-declared `readOnly` boolean to
`EvaluateDecisionCommand` (and both its callers: `EvaluateDecisionRequest`/
`PolicyDecisionController` for the synchronous API, `PolicyEvaluationRequestedPayload`/
`PolicyEvaluationRequestedEventMapper` for the async consumer) — defaults
`false`, so any caller that does not explicitly opt in keeps failing closed
exactly as before. When the evaluator throws against a real, already-fetched
effective `PolicyVersion` AND `readOnly` is `true`, `PolicyDecisionService#evaluate`
now calls the new `degradedCacheFallbackDecision` instead of `failSafeDecision`:
returns `effect=ALLOW`, `riskLevel=LOW`, `degraded=true`, `evaluationFailed=false`
(a real, if degraded, judgment was rendered — not a failure), bound to the
already-fetched version's own `policyId`/`policyVersion` ("the cache" is
simply that already-in-hand object — "decisions without policy version are
not allowed" still holds even in degraded mode), with two reason codes
(`EVALUATOR_UNAVAILABLE` plus the new `DEGRADED_CACHE_FALLBACK`, explaining
not just that the evaluator was down but why the effect is `ALLOW` anyway).
The "no effective version at all" branch is untouched by `readOnly` — that
remains `POLICY_VERSION_NOT_FOUND`/fail-closed unconditionally, since a
cache fallback needs a real version to bind to.

"Degraded metrics": added `GovernanceMetricsPort#recordPolicyDegraded(effect)`
(`governance_policy_degraded_total{effect}` in `MicrometerGovernanceMetrics`) —
distinct from the pre-existing `recordPolicyEvaluationFailure()`, which
fires on every evaluator/version failure regardless of cause (including
`POLICY_VERSION_NOT_FOUND`, which 10-failure-handling itself does not call
"degraded"); the new metric fires only when a produced decision's own
`degraded()` flag is actually `true`, letting an operator tell "the
evaluator is flaky" apart from "how many decisions degraded mode is
actually producing, and with which effect."

New tests: `PolicyDecisionServiceTest` gained 3 cases (`readOnly=true`
cache-fallback ALLOW with full field/metric assertions, `readOnly=false`
still fails closed exactly as before, no degraded metric on a normal
successful decision); `PolicyEvaluationRequestedEventMapperTest` and
`PolicyDecisionControllerTest` each gained a `readOnly` propagation
assertion on their existing happy-path test. New `RecordingGovernanceMetrics`
test double (mirrors `FakeMessageBrokerPublisher`'s own "records what
happened" shape) for asserting `recordPolicyDegraded` calls without
Mockito, consistent with every other application-layer test in this
service. 270/270 unit tests pass (./mvnw test, up from 267 after
SPEC-PG-031). Full verification (./mvnw verify, Docker available) also
passed: 311/311 total (270 unit + 3 PolicyEvaluationRequestedConsumerIT + 4
TicketApprovalRequiredConsumerIT + 3 ToolApprovalRequiredConsumerIT + 3
WorkflowApprovalRequiredConsumerIT + 4 GovernanceOutboxIT + 24
GovernancePersistenceIT Testcontainers Postgres/RabbitMQ integration). BUILD
SUCCESS. ArchUnit LayerDependencyTest 7/7. No schema change — every change
in this spec was pure Java (a new command field, a new reason code, a new
metric), so no new Flyway migration was needed.

SPEC-PG-033 (goal: "Implement expiry, poison decision review, outbox
replay, and startup recovery workers"). An audit against 10-failure-handling
§Poison Decision and §Recovery found two goal items already fully built,
one confirmed true by construction, and two genuine gaps.

Already done, confirmed not rebuilt: "expiry" (`ApprovalExpiryService`/
`ApprovalExpiryController`, SPEC-PG-012) and "outbox replay"
(`OutboxAdminService#dispatchPending`/`OutboxAdminController`, SPEC-PG-024).
Of §Poison Decision's four named triggers, two were already fully covered
("outbox publish repeatedly fails" — dead-letter + admin requeue,
SPEC-PG-024; "approval payload does not match source linkage" —
`ApprovalRequestMismatchException` on every grant/deny/cancel/use/revoke,
SPEC-PG-003/009) and one is true by construction, not by any explicit
check: "policy rule is incompatible with schema" cannot occur, since
`PolicyRuleDto`/`RuleConditionDto` are typed Java request DTOs — Jackson
rejects anything not matching that shape with a `400` before it ever
reaches the domain, so there is no free-form rule format that could ever
be "incompatible."

The two real gaps, both never built before this spec: "poison decision
review" (§Poison Decision's own remaining trigger, "same request repeatedly
crashes evaluator") and "startup recovery workers" (§Recovery's own ordered
5-step sequence, never orchestrated as one thing — only steps 1/2 existed
as separate admin actions). Added `PolicyDecisionRepository#findEvaluationFailed`
(`PolicyDecisionService#findPoisonDecisions`) and `OutboxEventRepository#findFailed`
— both pure review/report surfaces, never a repair action: decisions are
immutable (01-domain-model) and a poison outbox row still requires a
deliberate, accountable `OutboxAdminService#requeue` per row (SPEC-PG-024's
own design) — bulk auto-fixing either would defeat the point of "review."

New `RecoveryService#runRecovery` orchestrates the LLD's own ordered
sequence in one call: step 1 (`OutboxDispatchService#publishPending`) and
step 2 (`ApprovalExpiryService#expireDue`) are reused, not reimplemented;
step 3 ("check policy version consistency") is new —
`checkPolicyVersionConsistency` walks every `Policy` (new
`PolicyRepository#findAll`) and flags a `currentPublishedVersion` pointer
that either does not resolve to a real `PolicyVersion` row or resolves to
one whose own status is no longer `PUBLISHED`, the first time SPEC-PG-020's
own publish/supersede bookkeeping has ever been verified back (the same
"write-time invariant, never read-verified" gap SPEC-PG-031's chain
verification closed for the hash chain); step 4 ("reschedule poison
review") surfaces the poison-decision and dead-lettered-outbox counts/ids
above. Step 5 ("restore evaluator cache") is deliberately NOT built: no
evaluator cache exists anywhere in this codebase — `RuleEvaluatorPort` is
stateless and `PolicyVersionRepository#findEffectiveVersion` is always
fetched fresh from Postgres (09-concurrency-and-idempotency §Policy Version
Race relies on exactly this) — so there is nothing to restore, and
inventing a new caching layer this codebase has never had, for one LLD
word, would be scope creep.

New `RecoveryController`: `POST /api/v1/admin/recovery:run` (no
`@PreAuthorize` scope, mirroring `OutboxAdminController`'s own precedent
for this category of admin/deployment-triggered maintenance endpoint — "on
service startup" is satisfied the same way every other recovery piece in
this codebase already is, an external deployment script/orchestrator
calling an admin endpoint once at boot, not a new `ApplicationRunner`
pattern this codebase has never used) and `GET /api/v1/admin/recovery/poison-decisions`
(`SCOPE_governance:audit:read`, since a `PolicyDecision` is exactly the
kind of governance fact that scope already gates).

New tests: `RecoveryServiceTest` (new file) gained 8 cases (outbox
dispatch/approval expiry delegation, policy-version-consistency clean/
missing-version/wrong-status/never-published, poison decision count,
dead-lettered outbox ids); `PolicyDecisionServiceTest` gained 1
(`findPoisonDecisions` returns only genuinely `evaluationFailed` decisions);
new `RecoveryControllerSecurityTest` gained 5 (both endpoints'
authentication/scope requirements); `GovernancePersistenceIT` gained 3
round-tripping `findEvaluationFailed`/`findAll`/`findFailed` through real
Postgres columns. 284/284 unit tests pass (./mvnw test, up from 270 after
SPEC-PG-032). Full verification (./mvnw verify, Docker available) also
passed: 328/328 total (284 unit + 3 PolicyEvaluationRequestedConsumerIT + 4
TicketApprovalRequiredConsumerIT + 3 ToolApprovalRequiredConsumerIT + 3
WorkflowApprovalRequiredConsumerIT + 4 GovernanceOutboxIT + 27
GovernancePersistenceIT Testcontainers Postgres/RabbitMQ integration). BUILD
SUCCESS. ArchUnit LayerDependencyTest 7/7. No schema change — every new
port method reuses existing columns (`policy_decisions.evaluation_failed`,
`outbox_events.status`, `policies`), so no new Flyway migration was needed
(still V001-V025).

SPEC-PG-034 (goal: "Implement idempotency protection and admin-safe repair
flow for governance event replay/backfill") closes Phase 08. Idempotency
protection itself was already fully built and confirmed, not rebuilt:
`processed_events(eventId, consumerName)` dedup via
`ConsumedEventDeduplicationService` (SPEC-PG-025), used by all four inbound
consumers; outbox events with stable, replayable ids (SPEC-PG-003); and
per-command idempotency keys on cancel/use/revoke. The genuine gap was the
other half of the goal: an "admin-safe repair flow" for replay/backfill
never existed — before this spec, `ProcessedEventRepository` only ever
wrote `processed_events` (`markProcessedIfNew`), never read it back, and
there was no way to make an already-processed event acceptable again. 06
cannot force RabbitMQ to redeliver a message itself, so the buildable,
non-invented half of "backfill" is 06's own cooperating side: clearing its
own dedup ledger first, so that whatever redelivers the message (an
upstream domain's own replay/backfill) is not silently absorbed as a
no-op.

Added `application.model.ProcessedEventRecord` (this port's first-ever read
model) and two new `ProcessedEventRepository` methods: `findByEventId`
(review — every consumer that processed a given event) and
`deleteIfExists` (the repair action — clears one `(eventId, consumerName)`
marker, returning whether a row actually existed). New
`ProcessedEventAdminService` (kept separate from
`ConsumedEventDeduplicationService`, mirroring `OutboxAdminService`'s own
precedent for the same circular-dependency reason): `findByEventId` for
review, and `backfill(eventId, consumerName, actorId, reason,
correlationId)` — deletes the marker and writes a new
`PROCESSED_EVENT_BACKFILLED` audit record in the same transaction (SPEC-PG-001
domain rule), rejecting a pair that was never processed (`ProcessedEventNotFoundException`
-> `404`) rather than silently no-op'ing, mirroring `OutboxAdminService#requeue`'s
own "reject a row that doesn't apply" precedent. New migration V026 adds
`PROCESSED_EVENT_BACKFILLED` to `governance_audit_records`' own CHECK
constraint (same drop-and-recreate approach every prior audit-action
migration has used) — the only schema change this spec needed.
`ProcessedEventPersistenceAdapter#deleteIfExists` needed its own
`@Transactional` (a derived `deleteBy...` query has no transaction of its
own unless one is already open) — caught by `GovernancePersistenceIT`'s
own direct-repository-call test, the same class of bug SPEC-PG-024 fixed
for `OutboxEventPersistenceAdapter#requeue`.

New `ProcessedEventAdminController`: `GET /api/v1/admin/processed-events?eventId=`
(`SCOPE_governance:audit:read`, since this is a read of governance dedup
bookkeeping) and `POST /api/v1/admin/processed-events/{eventId}/{consumerName}:backfill`
(no `@PreAuthorize` scope, mirroring `OutboxAdminController`'s own
precedent for this category of admin maintenance endpoint).

New tests: `ProcessedEventAdminServiceTest` (new file) gained 5 cases
(review across multiple consumers, empty review, the actual
clear-then-reprocess-succeeds behavior, its own audit record, rejecting a
never-processed pair); new `ProcessedEventAdminControllerSecurityTest`
gained 7 (both endpoints' authentication/scope requirements, the 404
mapping, `@NotBlank reason` validation); `GovernancePersistenceIT` gained 1
round-tripping `findByEventId`/`deleteIfExists` through real Postgres,
including that a second delete against an already-absent row returns
`false`. 296/296 unit tests pass (./mvnw test, up from 284 after
SPEC-PG-033). Full verification (./mvnw verify, Docker available) also
passed: 341/341 total (296 unit + 3 PolicyEvaluationRequestedConsumerIT + 4
TicketApprovalRequiredConsumerIT + 3 ToolApprovalRequiredConsumerIT + 3
WorkflowApprovalRequiredConsumerIT + 4 GovernanceOutboxIT + 28
GovernancePersistenceIT Testcontainers Postgres/RabbitMQ integration). BUILD
SUCCESS. ArchUnit LayerDependencyTest 7/7.

SPEC-PG-035 (goal: "Build cross-domain contract tests with 02/03/04/05 and
approval lifecycle e2e harness") opens Phase 09. An audit against the
test-plan's own "Contract Tests" section (one line per upstream domain)
found the *inbound* half already fully covered — every one of the 4
consumed event shapes (05 Tool Gateway, 03 Agent Runtime, 02 Ticket
Workflow, 04 Memory Knowledge) already has both a pure mapper unit test
and a real RabbitMQ+Postgres consumer IT (SPEC-PG-025 through SPEC-PG-028)
— confirmed, not rebuilt. The *outbound* half had a real, previously
unnoticed gap: of the 7 real, graduated domain events this service
publishes, only 4 (`ApprovalRequestedEvent`/`ApprovalGrantedEvent`/
`ApprovalDeniedEvent`/`PolicyDecisionCreatedEvent`) had their own shape
test proving their JSON payload matches what 06-event-contracts documents
— `ApprovalExpiredEvent` (SPEC-PG-012), `ApprovalCancelledEvent`
(SPEC-PG-012), and `PolicyPublishedEvent` (SPEC-PG-020, the cache-invalidation
signal SPEC-PG-021 names by name) never did. Added `ApprovalExpiredEventTest`/
`ApprovalCancelledEventTest`/`PolicyPublishedEventTest`, mirroring
`ApprovalGrantedEventTest`'s own established shape (eventType/aggregateType/
aggregateId/payload field-by-field, plus a null-tolerance case for absent
optional fields).

"Approval lifecycle e2e harness": every earlier integration test exercises
exactly one step of the lifecycle in isolation — a consumer IT only the
inbound consume, `GovernanceOutboxIT` only the outbox stage-then-publish
path, `ApprovalServiceTest` only the in-memory decide step. New
`ApprovalLifecycleE2EIT` is the first test in this service chaining all
three against real Postgres + RabbitMQ in one flow: a real inbound
`tool.approval.required.v1` message creates a real `ApprovalRequest` via
the real `@RabbitListener`, a real `ApprovalService#grant`/`#deny` call
decides it (authenticating a real `JwtAuthenticationToken` through
`SecurityContextHolder`, since this test calls the service bean directly
under `webEnvironment = NONE` but must still pass the real RBAC/ABAC check
`JwtIdentityAuthorizationAdapter` performs), and
`OutboxDispatchService#publishPending` genuinely hands both the resulting
`approval.requested.v1` and `approval.granted.v1`/`approval.denied.v1`
events to the real broker. Delivery is verified through
`outbox_events.status = 'PUBLISHED'` plus the row's own `payload_json` —
SPEC-PG-003's own durability guarantee — rather than a second ad-hoc
consumer queue: this test's own real `@RabbitListener` containers are
concurrently active on the same shared connection factory, and an
anonymous drain queue declared alongside them proved unreliable in
practice (the broker reclaimed it mid-test); `GovernanceOutboxIT` can
safely use one because its own tests never trigger inbound consumption in
parallel.

New tests: `ApprovalExpiredEventTest`/`ApprovalCancelledEventTest`/
`PolicyPublishedEventTest` (new files) gained 2 cases each (6 total); new
`ApprovalLifecycleE2EIT` gained 2 (the full grant path and the full deny
path, each end to end). 302/302 unit tests pass (./mvnw test, up from 296
after SPEC-PG-034). Full verification (./mvnw verify, Docker available)
also passed: 349/349 total (302 unit + 3 PolicyEvaluationRequestedConsumerIT
+ 4 TicketApprovalRequiredConsumerIT + 3 ToolApprovalRequiredConsumerIT + 3
WorkflowApprovalRequiredConsumerIT + 4 GovernanceOutboxIT + 28
GovernancePersistenceIT + 2 ApprovalLifecycleE2EIT Testcontainers
Postgres/RabbitMQ integration). BUILD SUCCESS. ArchUnit LayerDependencyTest
7/7. No schema change — every change in this spec was pure test code (still
V001-V026).

SPEC-PG-036 (goal: "Complete LLD/phase/spec/implementation coverage audit,
release checklist, and residual risk register") closes Phase 09 and the
domain 06 implementation roadmap. Unlike every prior spec, this one adds no
production code — its own deliverable IS the audit: new
[coverage-audit-and-release-readiness.md](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-036-final-coverage-audit-release-readiness/coverage-audit-and-release-readiness.md)
covers (1) a spec coverage audit confirming all 36 `traceability-entry.yaml`
files across all 9 phases show `status: implemented`; (2) an LLD chapter
coverage audit confirming all 14 chapters (01-domain-model through
14-testing-strategy) are each mapped by at least one implemented spec; (3) a
release checklist (build/tests/ArchUnit/migrations/RBAC-ABAC/separation-of-
duties/idempotency/audit-trail/recovery/cross-domain-contracts, all passing,
no open blocking item); and (4) a residual risk register consolidating
every deliberate, previously-documented scope boundary across the whole
domain's history (9 items — e.g. ABAC's risk-level-only enforcement from
SPEC-PG-014, the never-built evaluator cache from SPEC-PG-033, the OTel
spans deliberately not built in SPEC-PG-029) into one place, each with why
it's open and what would trigger closing it. No item in the register is a
release blocker. Final verification (re-run at the end of this spec's own
work, no code changes since SPEC-PG-035): 302/302 unit, 349/349 total via
`./mvnw verify`, BUILD SUCCESS, ArchUnit `LayerDependencyTest` 7/7, Flyway
V001 through V026 applying cleanly. See
[SPEC-PG-001](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-001-policy-governance-module-and-package-boundaries/README_EN.md),
[SPEC-PG-002](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-002-policy-governance-schema-baseline/README_EN.md),
[SPEC-PG-003](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-003-governance-outbox-idempotency-audit-baseline/README_EN.md),
[SPEC-PG-006](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-006-decision-evaluate-api/README_EN.md),
[SPEC-PG-007](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-007-rule-evaluator-and-risk-mapping/README_EN.md),
[SPEC-PG-008](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-008-decision-constraints-and-policy-version-snapshot/README_EN.md),
[SPEC-PG-009](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-009-approval-request-aggregate/README_EN.md),
[SPEC-PG-010](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-010-approval-request-api-and-event/README_EN.md),
[SPEC-PG-011](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-011-approval-grant-deny-api/README_EN.md),
[SPEC-PG-012](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-012-approval-expiry-cancel/README_EN.md),
[SPEC-PG-013](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-013-approval-decision-event-publication/README_EN.md),
[SPEC-PG-014](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-014-rbac-abac-authorization/README_EN.md),
[SPEC-PG-015](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-015-separation-of-duties-check/README_EN.md),
[SPEC-PG-016](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-016-approval-authenticity-step-up/README_EN.md),
[SPEC-PG-017](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-017-sensitive-input-and-audit-redaction/README_EN.md),
[SPEC-PG-018](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-018-policy-draft-review-publish/README_EN.md),
[SPEC-PG-019](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-019-policy-version-immutability/README_EN.md),
[SPEC-PG-020](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-020-policy-deprecate-supersede-archive/README_EN.md),
[SPEC-PG-021](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-021-policy-cache-refresh-contract/README_EN.md),
[SPEC-PG-022](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-022-high-risk-override-lifecycle/README_EN.md),
[SPEC-PG-023](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-023-ticket-sla-exception-approval/README_EN.md),
[SPEC-PG-024](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-024-admin-repair-and-replay-approval/README_EN.md),
[SPEC-PG-025](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-025-tool-gateway-policy-approval-contract/README_EN.md),
[SPEC-PG-026](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-026-agent-runtime-governance-contract/README_EN.md),
[SPEC-PG-027](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-027-ticket-workflow-governance-contract/README_EN.md),
[SPEC-PG-028](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-028-memory-knowledge-policy-contract/README_EN.md),
[SPEC-PG-029](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-029-governance-metrics-logs-traces/README_EN.md),
[SPEC-PG-030](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-030-governance-audit-query-api/README_EN.md),
[SPEC-PG-031](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-031-audit-integrity-compliance-reporting/README_EN.md),
[SPEC-PG-032](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-032-policy-evaluator-failure-and-degraded-mode/README_EN.md),
[SPEC-PG-033](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-033-approval-expiry-poison-recovery-workers/README_EN.md),
[SPEC-PG-034](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-034-processed-event-replay-and-backfill/README_EN.md),
[SPEC-PG-035](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-035-governance-contract-e2e-harness/README_EN.md),
[SPEC-PG-036](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-036-final-coverage-audit-release-readiness/README_EN.md),
and the domain's
[13-package-and-class-design](../../docs/low-level-design/domains/06-policy-approval-governance/13-package-and-class-design/README_EN.md) /
[07-data-model](../../docs/low-level-design/domains/06-policy-approval-governance/07-data-model/README_EN.md).

## Prerequisites

- Java 21 (Temurin recommended)
- Docker (for Testcontainers-backed integration tests — Postgres and RabbitMQ)
- No local Maven installation required — use the bundled Maven Wrapper

## Run Tests

Fast unit tests only (in-memory test doubles, no Docker required):

```bash
./mvnw test
```

Full verification including Testcontainers-backed integration tests (real
Flyway migration run, real unique-constraint/concurrency checks, and a real
RabbitMQ publish-and-consume round trip):

```bash
./mvnw verify
```

## Build

```bash
./mvnw package
```

## Run Locally

Start the shared local Postgres/RabbitMQ first:

```bash
docker compose -f ../../infrastructure/docker-compose/local-platform.yml up -d
```

This service connects to the same shared Postgres instance/database as
`ticket-workflow-service` and `agent-runtime-service`, owning its own
`governance` schema within it, and publishes to the same shared
`opsmind.events` RabbitMQ topic exchange `ticket-workflow-service` already
declares and consumes from (see `application-local.yml`) — no separate
database, broker, or extra compose service is needed.

A real identity provider is required once `local` profile config supplies an
OAuth2 issuer; without one, boot the app with `spring.autoconfigure.exclude`
for `OAuth2ResourceServerAutoConfiguration` or point `KEYCLOAK_ISSUER_URI` at
a running Keycloak realm.

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Draining the outbox (`OutboxDispatchService#publishPending`) is intentionally
never invoked by a `@Scheduled` trigger in this codebase — call it from an
admin endpoint or an external scheduler once one exists; see that class's
own javadoc for why. The approval expiry worker follows the same
convention and now has its own endpoint: `POST /api/v1/approval-requests:expire-due`
(`ApprovalExpiryController`) — an external scheduler (e.g. a Kubernetes
CronJob) should call it on a fixed cadence.

## Package Layout

```text
src/main/java/com/opsmind/policygovernance/
  api/            REST controllers, DTOs, request-shape validation
  application/    Use-case services (@Transactional write paths), ports, commands, outbox model
  domain/         Policy, PolicyDecision, ApprovalRequest/Decision, GovernanceAuditRecord — no framework deps
  infrastructure/
    persistence/  Real JPA entities/mappers/adapters behind every repository port (Postgres, `governance` schema)
    messaging/    Real RabbitGovernanceEventPublisher (RabbitTemplate) behind MessageBrokerPublisherPort
    observability/ MicrometerGovernanceMetrics behind GovernanceMetricsPort
    evaluator/    Real rule-condition-matching evaluator (SPEC-PG-007)
    identity/     Real RBAC/ABAC IdentityAuthorizationPort adapter (JWT scopes + risk-clearance claim, SPEC-PG-014)
  config/         Security, OpenAPI, observability, Rabbit exchange topology, clock
  platform/error/ Shared error envelope + global exception handler
src/main/resources/db/migration/  Flyway migrations (V001-V026)
src/test/java/com/opsmind/policygovernance/support/  In-memory port doubles for fast unit tests
```

Architecture boundaries (domain has no Spring/JPA/AMQP dependency,
application never reaches into infrastructure, and nothing in this service
depends on another domain's own service package) are enforced by
`src/test/java/com/opsmind/policygovernance/architecture/LayerDependencyTest`.
