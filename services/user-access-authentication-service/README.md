# User Access And Authentication Service

## Service Purpose

`user-access-authentication-service` (domain 01) is OpsMind's identity and
authorization security boundary. External Keycloak is the sole source of
truth for credentials, OIDC/OAuth2, and MFA; this service owns trusted
identity mapping, role assignments, session/revocation metadata, step-up
evidence, workload identity, and authorization decisions — never passwords,
MFA secrets, raw tokens, or IdP private keys. Authorization denies by
default.

## Current Phase

**Phase 00 — Engineering Foundation — SPEC-UA-001 (Identity Module And
Package Boundaries) implemented.**

SPEC-UA-001 delivered the hexagonal service skeleton under package
`com.opsmind.identity`, following `docs/low-level-design/domains/01-user-access-authentication/13-package-and-class-design`'s
literal package tree:

```
domain/{user,role,session,stepup,workload,decision,audit,shared}
application/{command,query,service,dto,exception,port/{in,out}}
infrastructure/{persistence/adapter,keycloak,jwt,messaging,audit,clock,hashing}
api/{browser,internal,admin,event,error}
config
```

All six primary input ports (`ProvisionUserUseCase`,
`ManageRoleAssignmentUseCase`, `ManageSessionUseCase`, `ManageStepUpUseCase`,
`ManageServiceIdentityUseCase`, `EvaluateAuthorizationUseCase`) are
implemented with real, working domain logic — `UserIdentity`,
`RoleAssignment`, `UserSession`, `StepUpChallenge`, and `ServiceIdentity`
each have a real state machine (03-state-machine) enforcing their own legal
transitions, and `INV-UA-002` (deny by default) / step-up short-lived +
replay-resistance are enforced structurally in the domain layer, not just at
the API boundary.

Persistence is intentionally SPEC-UA-001-scoped in-memory adapters
(`infrastructure.persistence.adapter.InMemory*Repository`) — real
PostgreSQL/Flyway persistence is **SPEC-UA-002**'s job (Identity Schema
Baseline) and the real RabbitMQ outbox publisher is **SPEC-UA-003**'s
(Identity Outbox Processed Event And Audit Baseline); `pom.xml` deliberately
omits `spring-boot-starter-data-jpa`/`flyway`/`amqp` until then, mirroring
`policy-approval-governance-service`'s own SPEC-PG-001 precedent. The real
Keycloak OIDC login flow (`api.browser`), event consumers (`api.event`),
JWKS-backed JWT verification beyond the standard resource-server filter
chain, full claims normalization, the complete role/permission and
tenant/queue-scope model, the full authorization evaluation algorithm
(resource-scope/ownership/assurance legs), and real Keycloak MFA step-up are
each explicitly deferred to their own named specs (SPEC-UA-004 through
SPEC-UA-018) — see the relevant domain/application classes' own javadoc for
the exact boundary each defers.

ArchUnit (`test.architecture.LayerDependencyTest`) enforces the package
boundaries: `domain` has no Spring/JPA/AMQP/Security/Servlet dependency,
`application` never depends on `infrastructure`/`api`, `api` never depends
on `infrastructure`, and no class anywhere depends on another domain's own
service package (`ticketworkflow`/`toolgateway`/`agentruntime`/
`memoryknowledge`/`policygovernance`).

51/51 tests pass (`./mvnw test`); `./mvnw package` builds a runnable jar.

## Local Development

```
./mvnw test       # unit tests (domain + application + architecture)
./mvnw package     # build the runnable jar
```

`application-local.yml` points the OAuth2 resource server at the same local
Keycloak realm every other OpsMind service targets
(`KEYCLOAK_ISSUER_URI`, default `http://localhost:8081/realms/opsmind`);
Keycloak itself is not yet part of `infrastructure/docker-compose` — that
arrives with SPEC-UA-004.
