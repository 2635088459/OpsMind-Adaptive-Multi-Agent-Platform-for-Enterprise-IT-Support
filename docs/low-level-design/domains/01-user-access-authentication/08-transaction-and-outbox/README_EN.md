# 08 Transactions and Outbox

Aggregate state, audit record, and outbox event commit in one PostgreSQL transaction. Calls to Keycloak, RabbitMQ, or another domain are forbidden inside the database transaction.

| Command | Atomic writes |
|---|---|
| provision/disable user | user identity + audit + user event |
| assign/revoke role | role assignment + audit + role event |
| revoke session | session + audit + revocation event |
| verify/consume step-up | conditional challenge update + audit + assurance event |
| disable service identity | service identity + audit + event |

External Keycloak actions use local intent/outbox → adapter retry → reconciliation, never 2PC. Logout revokes locally first (fail closed), then best-effort notifies the IdP. The dispatcher claims batches with `FOR UPDATE SKIP LOCKED`, exponential backoff, and bounded attempts. Exhausted rows become `POISONED` and only an authorized admin API may requeue them. Successful publication is marked idempotently by event ID.

A consumer transaction first inserts `processed_events`, then mutates aggregates and writes local outbox. A unique-key conflict means already processed. Same event ID with a different payload hash is quarantined. Audit/outbox failure rolls back the business mutation.

---

> Domain: `01-user-access-authentication`  
> Service: `user-access-authentication-service`  
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`  
> Status: Detailed LLD  
> Spec mapping: `SPEC-UA-003, SPEC-UA-009, SPEC-UA-012, SPEC-UA-017, SPEC-UA-028`
