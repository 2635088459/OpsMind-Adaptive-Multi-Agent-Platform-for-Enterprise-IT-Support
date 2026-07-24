# OpsMind Ticket Workflow — Phase 00 Engineering Foundation

> **Document ID:** IMP-TW-P00  
> **Domain:** `02-ticket-workflow`  
> **Phase:** Phase 00  
> **Phase Name:** Engineering Foundation  
> **Version:** 1.1  
> **Status:** Reviewed Draft  
> **Prerequisite:** Ticket Workflow LLD 01–14 complete  
> **Next Phase:** Phase 01 — Create Ticket Vertical Slice  
> **Code Directory:** `services/ticket-workflow-service/`

---

# 1. Objective

Phase 00 establishes a Ticket Workflow Spring Boot project that:

```text
compiles
starts
runs tests
connects to local infrastructure
runs in CI
enforces architecture dependencies
```

This phase creates the implementation foundation. It does not implement Ticket business behavior.

---

# 2. Why Phase 00 Is Required

OpsMind uses:

```text
Spec-Driven Development
+
Test-Driven Development
+
Vertical Slice Delivery
```

TDD requires a trustworthy execution environment.

Before writing failing tests for `SPEC-TW-001 Create Ticket`, the repository needs:

- Java 21
- Spring Boot
- Maven Wrapper
- JUnit 5
- AssertJ
- Testcontainers
- PostgreSQL driver
- RabbitMQ client
- ArchUnit
- CI
- A stable package boundary
- Configuration files
- Health and readiness checks

Without Phase 00, a failing test cannot be reliably classified as:

```text
a business implementation failure
or
a build, dependency, container, configuration, or CI failure
```

Phase 00 makes the testing platform trustworthy first.

---

# 3. What Phase 00 Is Not

Phase 00 is not:

- Create Ticket
- Ticket state-machine implementation
- Ticket business-table implementation
- Business RabbitMQ event consumption
- Full Keycloak realm configuration
- Transactional Outbox implementation
- Agent Runtime integration
- Tool Gateway integration
- Reconciliation implementation

No business API exists at the end of Phase 00.

That is intentional.

---

# 4. Design References

## `13-package-and-class-design`

Applies:

- Root package
- Package-by-feature
- Hexagonal dependency direction
- Configuration package
- Test package
- ArchUnit

## `14-testing-strategy`

Applies:

- Test tags
- Static analysis
- Unit-test environment
- Testcontainers
- CI Fast Verify
- Architecture tests
- Test reports

## `12-observability-and-audit`

Phase 00 creates only:

- Actuator
- Basic health
- Structured-logging foundation
- OpenTelemetry foundation

It does not implement business audit.

## `11-security-and-authorization`

Phase 00 creates only:

- Spring Security dependencies
- A default-deny security baseline
- Test-environment security support

It does not implement the full Keycloak authorization matrix.

## Technology Baseline

Applies:

- Java 21
- Spring Boot
- PostgreSQL
- RabbitMQ
- Docker Compose
- Maven

---

# 5. Phase Principles

## 5.1 Do Not Implement Business Behavior Early

Do not create:

```text
Ticket.java
TicketStatus.java
CreateTicketController.java
TicketJpaEntity.java
```

These belong to Phase 01.

## 5.2 Do Not Add Meaningless Placeholder Classes

Do not create:

```text
CommonService
BaseController
GenericRepository
Utils
Helper
```

without a real responsibility.

## 5.3 Every Engineering Capability Has a Verification Test

Examples:

- PostgreSQL Testcontainer has a connectivity test.
- RabbitMQ Testcontainer has a connectivity test.
- ArchUnit executes a real rule.
- Spring context has a startup test.
- Actuator has a health test.
- The container image is started during verification.

## 5.4 Pin Versions

Do not use:

```text
latest
```

for infrastructure images.

Java, Spring Boot, PostgreSQL, RabbitMQ, Keycloak, Maven, and Testcontainers versions are explicit.

## 5.5 Keep Local and CI Aligned

Local infrastructure primarily uses Docker Compose.

CI primarily uses Testcontainers.

Environment-variable names remain consistent.

---

# 6. Phase Directory Deliverable

Phase 00 creates the minimum physical project structure:

```text
services/
└── ticket-workflow-service/
    ├── .mvn/
    ├── mvnw
    ├── mvnw.cmd
    ├── pom.xml
    ├── Dockerfile
    ├── README.md
    │
    ├── src/
    │   ├── main/
    │   │   ├── java/
    │   │   │   └── dev/opsmind/ticketworkflow/
    │   │   │       ├── TicketWorkflowApplication.java
    │   │   │       ├── configuration/
    │   │   │       └── platform/
    │   │   └── resources/
    │   │       ├── application.yml
    │   │       ├── application-local.yml
    │   │       └── logback-spring.xml
    │   │
    │   └── test/
    │       ├── java/dev/opsmind/ticketworkflow/
    │       │   ├── TicketWorkflowApplicationTest.java
    │       │   ├── architecture/
    │       │   ├── infrastructure/
    │       │   └── support/
    │       └── resources/
    │           └── application-test.yml
```

The business package structure is defined by Document 13 and is created as Feature Specs require it:

```text
ticket/api
ticket/application
ticket/domain
ticket/infrastructure
reconciliation
audit
```

Rules:

- Do not create large numbers of empty packages for presentation.
- Do not put `application-test.yml` in `src/main/resources`.
- Prefer the monorepo root `.gitignore`.
- Add `docker/entrypoint.sh` only when an actual startup requirement exists.

---

# 7. Frozen Technical Baseline

This implementation line uses:

```text
Java: 21
Spring Boot: 3.5.16
Maven Wrapper: 3.9.16
PostgreSQL: 18.4
RabbitMQ: 4.3.4-management
Keycloak: 26.7.0
Testcontainers: 2.0.5
```

Decision notes:

- Java 21 matches the approved design.
- Spring Boot 3.5.16 remains aligned with the existing `3.5.x` baseline.
- Spring Boot 4.1.0 is a different major line and is not adopted silently.
- Testcontainers uses the version managed by Spring Boot 3.5.16.
- PostgreSQL, RabbitMQ, and Keycloak use pinned patch versions; production should additionally pin image digests.
- Maven 4 is not the selected GA baseline, so Maven 3.9.16 Wrapper is used.

Upgrade process:

```text
Technology Baseline Update
→ Compatibility Test
→ ADR for major or behavior-changing upgrades
→ CI Verification
```

---

# 8. Maven Dependencies

Use the Spring Boot parent or BOM to manage supported dependency versions. Do not override Spring ecosystem versions without a reviewed compatibility reason.

## 8.1 Runtime Foundation

Phase 00 includes:

```text
spring-boot-starter-web
spring-boot-starter-security
spring-boot-starter-oauth2-resource-server
spring-boot-starter-data-jpa
spring-boot-starter-amqp
spring-boot-starter-actuator
org.postgresql:postgresql
org.flywaydb:flyway-core
org.flywaydb:flyway-database-postgresql
io.micrometer:micrometer-registry-prometheus
io.micrometer:micrometer-tracing-bridge-otel
io.opentelemetry:opentelemetry-exporter-otlp
```

Notes:

- Add `spring-boot-starter-validation` in Phase 01 with the first API Spec.
- PostgreSQL support for Flyway uses the dedicated `flyway-database-postgresql` module.
- Metrics use Micrometer and Prometheus.
- Tracing uses Micrometer Observation and Tracing with the OpenTelemetry bridge and OTLP exporter.
- Phase 00 does not also enable the OpenTelemetry Java Agent. Introducing the agent later requires an ADR and duplicate-span tests.

## 8.2 Test Foundation

```text
spring-boot-starter-test
org.springframework.security:spring-security-test
org.springframework.boot:spring-boot-testcontainers
org.testcontainers:testcontainers-junit-jupiter
org.testcontainers:testcontainers-postgresql
org.testcontainers:testcontainers-rabbitmq
com.tngtech.archunit:archunit-junit5
org.awaitility:awaitility
```

AssertJ is already provided by `spring-boot-starter-test` and is not declared again.

## 8.3 Add Later When Required

```text
MapStruct
JSON Schema Validator
PIT
WireMock
Cucumber
Pact
```

Every new dependency must correspond to an actual specification, test, or implementation need.

---

# 9. Maven Plugins

Recommended:

```text
maven-compiler-plugin
maven-surefire-plugin
maven-failsafe-plugin
jacoco-maven-plugin
spring-boot-maven-plugin
maven-enforcer-plugin
spotbugs-maven-plugin
```

Optional:

```text
spotless-maven-plugin
```

## Test Naming

Surefire:

```text
*Test.java
```

Failsafe:

```text
*IT.java
```

Commands:

```bash
./mvnw test
./mvnw verify
```

---

# 10. Maven Enforcer

Validate:

- Java 21
- Minimum Maven version
- Dependency conflicts
- No snapshot dependency in a release
- Dependency convergence when enabled

Build-environment errors should fail before runtime.

---

# 11. Spring Boot Application

Create:

```text
dev.opsmind.ticketworkflow.TicketWorkflowApplication
```

Its only responsibility is to start Spring Boot.

It does not contain:

- Queue bindings
- Security rules
- Schedulers
- Seed data
- Domain logic
- Test fixtures

---

# 12. Configuration Strategy

Configuration files:

```text
application.yml
application-local.yml
src/test/resources/application-test.yml
```

## `application.yml`

Contains environment-independent defaults:

- Application name
- Actuator
- Jackson
- JPA validation
- Basic logging
- Graceful shutdown

## `application-local.yml`

Reads environment variables:

```text
DB_HOST
DB_PORT
DB_NAME
DB_USERNAME
DB_PASSWORD
RABBITMQ_HOST
RABBITMQ_PORT
RABBITMQ_USERNAME
RABBITMQ_PASSWORD
KEYCLOAK_ISSUER_URI
OTEL_EXPORTER_OTLP_ENDPOINT
```

## `application-test.yml`

- Disables real telemetry export
- Uses Testcontainers dynamic properties
- Disables nonessential schedulers
- Keeps Flyway enabled
- Uses explicit test security configuration

## Secrets

Commit:

```text
.env.example
```

Do not commit:

```text
.env
```

---

# 13. Configuration Properties

Prefer Spring Boot’s built-in properties:

```text
spring.datasource.*
spring.rabbitmq.*
spring.security.oauth2.resourceserver.*
management.*
```

Create custom `@ConfigurationProperties` only for actual project-specific settings, such as:

```text
OpsMindServiceProperties
TelemetryRedactionProperties
```

Do not create empty configuration classes for hypothetical future behavior, and avoid scattered `@Value` fields.

---

# 14. Local Infrastructure

The repository uses the existing top-level:

```text
infrastructure/
```

Recommended structure:

```text
infrastructure/
├── docker-compose/
│   └── local-platform.yml
├── postgres/
├── rabbitmq/
├── keycloak/
└── observability/
```

Phase 00 requires:

- PostgreSQL
- RabbitMQ

Keycloak and the OTel Collector may have basic local definitions, but full realms, dashboards, and alerts are later work.

---

# 15. Docker Compose

Minimum local services:

```text
postgres
rabbitmq
```

Optional:

```text
keycloak
otel-collector
prometheus
grafana
```

Requirements:

- Pinned image version
- Health check
- Named volume
- Explicit ports
- Credentials from environment variables
- No production secrets
- Stable service names
- One-command startup

Example:

```bash
docker compose -f infrastructure/docker-compose/local-platform.yml up -d
```

---

# 16. PostgreSQL Foundation

Phase 00 does not create Ticket business tables.

It verifies:

- JDBC driver availability
- Datasource configuration
- PostgreSQL Testcontainer startup
- Flyway initialization
- JPA context startup

Create:

```text
src/main/resources/db/migration/
```

but do not create a placeholder `V000` migration.

The first real migration begins in Phase 01 and follows `07-data-model`.

---

# 17. RabbitMQ Foundation

Phase 00 does not define complete Ticket business topology.

It verifies:

- Spring AMQP connectivity
- RabbitMQ Testcontainer startup
- Connection Factory initialization
- Listener infrastructure initialization

Full topology such as:

```text
opsmind.events
retry queues
DLQs
bindings
```

is implemented from the approved Event Contract in Phase 01 or Phase 03.

If the global exchange is already frozen by HLD, infrastructure may declare it, but Ticket Workflow does not publish a business event in Phase 00.

---

# 18. Security Foundation

Phase 00 includes Spring Security but not the complete role and scope matrix.

Recommended policy:

```text
Default Deny
+
Explicit Health Access
```

Define access to:

```text
/actuator/health
/actuator/info
```

Do not use a global:

```text
permitAll()
```

to simplify startup.

Future business APIs use Keycloak JWTs.

Tests may use:

- Mock JWT
- Test security configuration
- Stable test claims

but must not bypass application authorization design.

---

# 19. Observability Foundation

Phase 00 implements:

- `spring.application.name=ticket-workflow-service`
- Actuator
- Prometheus endpoint
- Micrometer Observation and Tracing
- OpenTelemetry bridge and OTLP export
- Basic structured logging
- W3C trace-context foundation
- Health indicators

It does not implement:

- Ticket business metrics
- Outbox dashboards
- Security audit
- LangSmith integration

## Logging

At minimum:

```text
timestamp
level
service
environment
logger
message
traceId when available
```

Never log:

- Environment secrets
- Authorization headers
- Raw configuration
- Complete environment dumps

---

# 20. Health and Readiness

Minimum endpoints:

```text
/actuator/health
/actuator/info
/actuator/prometheus
```

Distinguish:

- Liveness
- Readiness

Local and test verification includes:

- Application up
- PostgreSQL readiness
- RabbitMQ readiness

Whether every dependency belongs directly in Kubernetes readiness is a later platform decision. A temporary dependency outage should not automatically create restart loops.

---

# 21. Architecture Tests

Create:

```text
src/test/java/.../architecture/LayerDependencyTest.java
```

Minimum rules:

```text
domain does not depend on Spring
domain does not depend on JPA
application does not depend on infrastructure implementations
api does not access persistence repositories
```

The rule entry point exists in Phase 00. Real business classes are protected beginning in Phase 01.

---

# 22. Testcontainers Foundation

Create shared test support:

```text
src/test/java/.../support/PostgresContainerSupport.java
src/test/java/.../support/RabbitMqContainerSupport.java
```

or a focused combined support class.

## PostgreSQL Verification

- Container starts
- JDBC connection succeeds
- Flyway runs
- Spring context receives dynamic properties

## RabbitMQ Verification

- Container starts
- Connection succeeds
- Spring AMQP initializes

Do not depend on fixed host ports.

---

# 23. Foundation Test Inventory

Required:

```text
TicketWorkflowApplicationTest
PostgresConnectivityIT
RabbitMqConnectivityIT
LayerDependencyTest
ActuatorHealthIT
ConfigurationPropertiesTest
```

## `TicketWorkflowApplicationTest`

Verifies Spring context startup.

## `PostgresConnectivityIT`

Uses a real PostgreSQL Testcontainer.

## `RabbitMqConnectivityIT`

Uses a real RabbitMQ Testcontainer.

## `LayerDependencyTest`

Runs ArchUnit.

## `ActuatorHealthIT`

Verifies health, liveness, and readiness.

## `ConfigurationPropertiesTest`

Verifies:

- Missing required configuration fails safely.
- Configuration errors do not expose secrets.
- Profiles are selected correctly.

---

# 24. CI Fast Verify

Recommended workflow:

```text
.github/workflows/ticket-workflow-ci.yml
```

When a shared monorepo workflow already exists, add a Ticket Workflow job.

Minimum Pull Request steps:

```text
checkout
setup Java 21
cache Maven
./mvnw -B clean verify
upload unit and integration test reports
```

Phase 00 may begin with one `verify` job.

Later stages can separate:

- Fast Verify
- Integration
- Contract
- E2E

---

# 25. CI Path Filters

Run the Ticket Workflow job when these areas change:

```text
services/ticket-workflow-service/**
packages/event-contracts/**
packages/api-contracts/**
infrastructure/**
docs/specs/domains/02-ticket-workflow/**
docs/low-level-design/domains/02-ticket-workflow/**
docs/implementation-plans/domains/02-ticket-workflow/**
```

Do not make filters so narrow that shared configuration changes skip verification.

---

# 26. Static Analysis

Phase 00 minimum:

- Compiler warnings
- Maven Enforcer
- ArchUnit
- Dependency vulnerability scanning or Dependabot
- Secret scanning

SpotBugs and Checkstyle may be introduced in Phase 00, but rules should remain maintainable rather than creating configuration work with little value.

Principle:

```text
Establish a sustainable quality gate first,
then increase strictness incrementally.
```

---

# 27. Dockerfile

Use a multi-stage build:

```text
Build Stage
→ Runtime Stage
```

Requirements:

- Pinned Java 21 image
- Non-root runtime user
- Only runtime artifact copied
- No Maven cache, source, or secrets in runtime image
- Exposed application port
- Container health check
- JVM options through environment variables

Phase 00 verifies:

```text
the image builds
the container starts
the health check passes
```

---

# 28. README

`services/ticket-workflow-service/README.md` includes:

- Service purpose
- Current phase
- Prerequisites
- Local startup
- Unit tests
- Integration tests
- Local infrastructure startup
- Docker image build
- Configuration variables
- Current non-goals
- Design links
- Next phase

Example:

```bash
./mvnw test
./mvnw verify
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
docker build -t opsmind/ticket-workflow-service:local .
```

---

# 29. TDD Order for Phase 00

Phase 00 is infrastructure-focused but still uses tests first.

## RED 1

Write:

```text
TicketWorkflowApplicationTest
```

## GREEN 1

Create the main class and minimum Spring configuration.

## RED 2

Write the PostgreSQL connectivity integration test.

## GREEN 2

Add JDBC, Testcontainers, Datasource, and Flyway.

## RED 3

Write the RabbitMQ connectivity test.

## GREEN 3

Add Spring AMQP and container configuration.

## RED 4

Write ArchUnit rules.

## GREEN 4

Create the minimal package structure.

## RED 5

Write the Actuator health test.

## GREEN 5

Add Actuator and health configuration.

## RED 6

Write a Docker startup smoke test or verification script.

## GREEN 6

Complete the multi-stage Dockerfile.

## REFACTOR

- Consolidate test support.
- Extract only necessary configuration properties.
- Improve naming.
- Update README.
- Remove unused dependencies.

---

# 30. Implementation Tasks

## P00-T01 Create Service Directory

```text
services/ticket-workflow-service/
```

## P00-T02 Initialize Java 21 Spring Boot

- Maven Wrapper
- Dependency management
- Application class

## P00-T03 Create Minimal Package Structure

Follow Document 13 without creating meaningless empty directories.

## P00-T04 Configure Runtime Dependencies

Include only required current and near-term dependencies.

## P00-T05 Configure Test Dependencies

JUnit, AssertJ through Spring Boot Test, Testcontainers, ArchUnit, and Awaitility.

## P00-T06 Configure Profiles

```text
default
local
test
```

## P00-T07 PostgreSQL Testcontainer

Connectivity and Flyway foundation.

## P00-T08 RabbitMQ Testcontainer

Connectivity foundation.

## P00-T09 Security Baseline

Default deny with explicit health exceptions.

## P00-T10 Actuator and Logging

Health, info, Prometheus, tracing foundation, and structured logging.

## P00-T11 Architecture Test

Package dependency rules.

## P00-T12 CI Workflow

Pull Request verification.

## P00-T13 Dockerfile

Build and startup verification.

## P00-T14 README

Local development instructions.

---

# 31. Recommended Pull Request Split

Phase 00 may use one Pull Request or two small Pull Requests.

## PR 1 — Project Bootstrap

```text
build(ticket): bootstrap Spring Boot service
test(ticket): add context and architecture tests
docs(ticket): add service README
```

## PR 2 — Infrastructure Test Foundation

```text
test(ticket): add PostgreSQL and RabbitMQ containers
build(ticket): add actuator and container image
ci(ticket): add verify workflow
```

Do not combine Phase 00 and Create Ticket in the same Pull Request.

---

# 32. Deliverables

Code:

```text
services/ticket-workflow-service/
```

Infrastructure adjustments:

```text
infrastructure/docker-compose/
```

CI:

```text
.github/workflows/
```

Documentation:

```text
services/ticket-workflow-service/README.md
docs/implementation-plans/domains/02-ticket-workflow/phase-00-engineering-foundation_EN.md
```

Test reports:

- Context startup
- PostgreSQL integration
- RabbitMQ integration
- ArchUnit
- Health

---

# 33. Risks and Mitigations

## Risk 1 — Overbuilding Foundation

Symptoms:

- Phase 00 takes weeks.
- Every production platform is configured early.
- No business progress occurs.

Mitigation:

```text
Implement only the capabilities required by Phase 01.
```

## Risk 2 — Too Many Dependencies

Mitigation:

- Defer optional dependencies.
- Require a concrete use case for every dependency.

## Risk 3 — Security Is Disabled for Convenience

Mitigation:

- Default deny.
- Explicit health access only.
- Explicit test security configuration.

## Risk 4 — Testcontainers Is Unstable in CI

Mitigation:

- Pin images.
- Preserve container logs.
- Distinguish infrastructure startup failure from business-test failure.
- Avoid fixed host ports.

## Risk 5 — Empty Package Explosion

Mitigation:

- Create only root and required packages.
- Add business packages with Feature Specs.

## Risk 6 — Business Scope Leaks into Phase 00

Mitigation:

- Review Non-goals.
- Reject Ticket aggregate, controller, and business-table changes in Phase 00 PRs.

---

# 34. Cross-domain Dependency Policy

Phase 00 does not require real services from other domains.

Later Ticket Workflow phases use:

```text
Approved Event or API Contract
→ Golden Fixture
→ Deterministic Stub
→ Ticket Workflow Integration Test
→ Real Service Compatibility Test
```

Rules:

- Stubs follow approved contracts.
- Stubs do not access the Ticket Workflow database.
- Outcomes are scenario-controlled, not random.
- Real Agent, Approval, Tool, and Verification services reuse the same contract tests.
- Consumers do not silently accept arbitrary payloads from an invalid producer.

---

# 35. Phase 00 Scope Review

## Retained

- Spring Boot skeleton
- PostgreSQL and RabbitMQ Testcontainers
- JPA, Flyway, and AMQP foundations
- Spring Security default deny
- Actuator, Prometheus, and tracing foundation
- ArchUnit
- CI
- Docker image
- README

## Deferred

- Ticket business code
- Business migrations
- Business RabbitMQ topology
- Full Keycloak realm
- Dashboards and alerts
- Audit tables
- Outbox Publisher
- Feature contract tests

## Removed or Corrected

- `application-test.yml` exists only in `src/test/resources`.
- No placeholder `V000` migration.
- No complete empty business package tree.
- No simultaneous OTel Java Agent and Micrometer OTel bridge.
- No duplicate AssertJ declaration.
- CI runs one `clean verify` instead of executing the same tests twice.

---

# 36. Non-goals

Phase 00 does not deliver:

```text
POST /api/v1/tickets
Ticket Aggregate
TicketStatus
Ticket business tables
Outbox business tables
Business RabbitMQ event
Keycloak production realm
Agent integration
Tool integration
Verification
Reconciliation
```

These belong to later phases.

---

# 37. Exit Criteria

All criteria must pass.

## Build

```text
./mvnw clean verify
```

passes.

## Application

- Spring Boot context starts.
- `spring.application.name` is correct.
- Local profile starts.
- No Ticket business endpoint exists.

## PostgreSQL

- Testcontainer starts.
- Datasource connects.
- Flyway initializes.
- No H2 dependency is used.

## RabbitMQ

- Testcontainer starts.
- Connection succeeds.
- Spring AMQP initializes.

## Architecture

- ArchUnit executes.
- Domain has no Spring or JPA dependency.
- Application has no infrastructure-implementation dependency.
- API does not directly access persistence.

## Security

- Unauthorized requests are not globally permitted.
- Health access policy is explicit.
- Test credentials are not committed.

## Observability

- Actuator health is available.
- Prometheus endpoint is configured.
- Logs do not expose secrets.

## CI

- Pull Request workflow executes.
- Reports are available.

## Docker

- Image builds.
- Container starts.
- Health check passes.

## Documentation

- Service README is complete.
- Environment variables are documented.
- Current non-goals are explicit.
- The next phase is linked.

---

# 38. Exit Review Checklist

- [ ] Java 21 is frozen.
- [ ] Spring Boot version is frozen.
- [ ] Maven Wrapper is committed.
- [ ] Root package follows Document 13.
- [ ] `./mvnw test` passes.
- [ ] `./mvnw verify` passes.
- [ ] PostgreSQL Testcontainer passes.
- [ ] RabbitMQ Testcontainer passes.
- [ ] ArchUnit passes.
- [ ] Health and readiness pass.
- [ ] Prometheus endpoint is available.
- [ ] Docker image starts.
- [ ] CI passes.
- [ ] No Ticket business code entered early.
- [ ] README is complete.
- [ ] Secret scan passes.
- [ ] No `latest` image is used.
- [ ] Traceability records Phase 00 status.

---

# 39. What Phase 00 Enables

Only after the Exit Review may the project enter:

```text
Phase 01 — Create Ticket Vertical Slice
```

Next sequence:

```text
1. Write SPEC-TW-001-create-ticket_EN.md
2. Write the Phase 01 plan
3. Write failing domain tests
4. Implement the minimum Ticket creation domain
5. Create Flyway business migrations
6. Implement the persistence adapter
7. Implement the Create Ticket API
8. Implement transaction, history, audit, and Outbox
9. Complete integration and contract verification
10. Update the traceability matrix
```

---

# 40. Definition of Done

Phase 00 is complete when:

```text
OpsMind has a trustworthy, repeatable, architecture-constrained
Ticket Workflow development platform that can begin implementing
the first business vertical slice through Feature Specs and TDD.
```

It does not mean Ticket Workflow already has business functionality.
