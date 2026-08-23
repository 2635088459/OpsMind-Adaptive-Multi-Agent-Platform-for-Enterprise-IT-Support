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
SPEC-PG-008). Phase 02 — Approval Lifecycle — SPEC-PG-009/010/011 done,
SPEC-PG-012 onward pending.**

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

The full identity/separation-of-duties model
(phase-03) is still a fail-closed placeholder — see `StubIdentityAuthorizationAdapter`'s own
javadoc for what it defers and to which future spec. See
[SPEC-PG-001](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-001-policy-governance-module-and-package-boundaries/README_EN.md),
[SPEC-PG-002](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-002-policy-governance-schema-baseline/README_EN.md),
[SPEC-PG-003](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-003-governance-outbox-idempotency-audit-baseline/README_EN.md),
[SPEC-PG-006](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-006-decision-evaluate-api/README_EN.md),
[SPEC-PG-007](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-007-rule-evaluator-and-risk-mapping/README_EN.md),
[SPEC-PG-008](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-008-decision-constraints-and-policy-version-snapshot/README_EN.md),
[SPEC-PG-009](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-009-approval-request-aggregate/README_EN.md),
[SPEC-PG-010](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-010-approval-request-api-and-event/README_EN.md),
[SPEC-PG-011](../../docs/specs/domains/06-policy-approval-governance/SPEC-PG-011-approval-grant-deny-api/README_EN.md),
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
own javadoc for why.

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
    identity/     Fail-closed identity/authorization placeholder (real model is phase-03)
  config/         Security, OpenAPI, observability, Rabbit exchange topology, clock
  platform/error/ Shared error envelope + global exception handler
src/main/resources/db/migration/  Flyway migrations (V001-V013)
src/test/java/com/opsmind/policygovernance/support/  In-memory port doubles for fast unit tests
```

Architecture boundaries (domain has no Spring/JPA/AMQP dependency,
application never reaches into infrastructure, and nothing in this service
depends on another domain's own service package) are enforced by
`src/test/java/com/opsmind/policygovernance/architecture/LayerDependencyTest`.
