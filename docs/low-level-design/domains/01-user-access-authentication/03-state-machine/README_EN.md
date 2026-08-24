# 03 State Machines

## `UserIdentity`

```text
ACTIVE ──disable──> DISABLED ──enable──> ACTIVE
   └────deprovision───────────> DEPROVISIONED (final)
DISABLED ──deprovision────────> DEPROVISIONED
```

`DEPROVISIONED` is irreversible. Rehire creates a new mapping or uses an explicit re-link process; old authority is never silently restored.

## `RoleAssignment`

```text
PENDING ──activate(validFrom)──> ACTIVE ──revoke──> REVOKED
   └────────cancel─────────────> CANCELLED
ACTIVE ──validUntil reached────> EXPIRED
```

`REVOKED`, `EXPIRED`, and `CANCELLED` are final. Overlapping ACTIVE assignments for the same user, role, and scope are prevented by constraint plus transactional validation.

## `UserSession`

```text
ACTIVE ──expiry──> EXPIRED
ACTIVE ──logout/admin revoke──> REVOKED
ACTIVE ──security signal──────> COMPROMISED
ACTIVE ──normal termination───> TERMINATED
```

Every non-ACTIVE state is final. Token refresh may update controlled metadata or create a successor session but cannot undo revocation.

## `StepUpChallenge`

```text
REQUESTED → PENDING → VERIFIED → CONSUMED
                 ├──attempt limit→ FAILED
                 ├──timeout──────> EXPIRED
                 └──cancel───────> CANCELLED
```

`VERIFIED → CONSUMED` uses a conditional update `WHERE status='VERIFIED' AND expires_at>now()` so only one concurrent consumer succeeds. Action/resource mismatch preserves state and writes a denial audit.

## `ServiceIdentity`

`ACTIVE → DISABLED → RETIRED`; `RETIRED` is final. Identities beyond `validUntil` are invalid during authorization and reconciliation moves them to `RETIRED`.

Every legal transition writes domain audit and outbox in the same transaction. Illegal transitions return stable error codes without revealing object existence to unauthorized callers.

---

> Domain: `01-user-access-authentication`  
> Service: `user-access-authentication-service`  
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`  
> Status: Detailed LLD  
> Spec mapping: `SPEC-UA-002, SPEC-UA-009, SPEC-UA-012, SPEC-UA-017`
