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
SPEC-PG-013). Phase 03 — Security Separation Of Duties — SPEC-PG-014/015 done,
SPEC-PG-016 onward pending.**

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
to check separation of duties against. See
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
src/main/resources/db/migration/  Flyway migrations (V001-V016)
src/test/java/com/opsmind/policygovernance/support/  In-memory port doubles for fast unit tests
```

Architecture boundaries (domain has no Spring/JPA/AMQP dependency,
application never reaches into infrastructure, and nothing in this service
depends on another domain's own service package) are enforced by
`src/test/java/com/opsmind/policygovernance/architecture/LayerDependencyTest`.
