# 02 Business Invariants

## Identity and trust

1. Only allowlisted HTTPS issuers may create principals; signature, `iss`, `aud`, `exp`, `nbf`, and token type are mandatory validations.
2. `(tenantId, issuer, subject)` is the stable user identity. Username, email, display name, and user text grant no authority.
3. Passwords, MFA secrets, raw tokens, session cookies, and IdP private keys are forbidden in storage, logs, and events.
4. `DISABLED`/`DEPROVISIONED` users, disabled workloads, and revoked/expired sessions fail closed.

## Authorization

5. Deny by default. Allow requires the intersection of trusted principal, active role assignment, resource scope, ownership rule, and assurance requirement.
6. `SELF` permits only resources mapped to the token subject; a request-body `userId` cannot expand access.
7. Roles, tenant, subject, and step-up flags supplied through client headers are untrusted; only verified token claims and server-side mappings produce them.
8. Domain 01 decides identity-level access and domain 06 decides risk, approval, and business governance. An 01 `ALLOW` is never tool-execution authority.
9. A role grantor cannot delegate beyond its own grant scope. Domain 01 supplies separation-of-duties identity facts; domain 06 makes the governance decision.

## Step-up and sessions

10. Step-up evidence binds issuer, subject, session, action, resource, assurance, and expiry and is single use.
11. Verification, consumption, and replay rejection are atomic; an expired challenge can never return to `VERIFIED`.
12. Revocation propagates with eventual consistency. Sensitive operations deny when revocation is unknown, JWKS cannot refresh safely, or IdP trust fails.

## Audit and privacy

13. Role changes, session revocations, step-up, break-glass, and authorization decisions record actor, subject, reason, correlation, before/after, and outcome.
14. PII is purpose-minimized. After profile deletion, de-identified security audit may remain but must not reconstruct the original identity.
15. Retries and duplicate events never create duplicate roles, repeated challenge consumption, or conflicting decisions.

---

> Domain: `01-user-access-authentication`  
> Service: `user-access-authentication-service`  
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`  
> Status: Detailed LLD  
> Spec mapping: `SPEC-UA-001, SPEC-UA-006, SPEC-UA-011, SPEC-UA-013, SPEC-UA-015, SPEC-UA-018, SPEC-UA-019`
