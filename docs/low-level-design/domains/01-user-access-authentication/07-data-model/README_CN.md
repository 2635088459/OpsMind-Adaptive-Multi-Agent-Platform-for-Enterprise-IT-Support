# 07 数据模型

PostgreSQL 使用独立 `identity` schema；所有业务表含 `tenant_id`, `created_at`, `updated_at`（不可变事实除外）和需要时的 `version`。

| 表 | 核心列 | 约束/索引 |
|---|---|---|
| `user_identities` | `user_identity_id`, tenant, issuer, subject, username, display_name, email, type, status, profile_version, linked/synced/disabled/deprovisioned timestamps, version | UNIQUE `(tenant_id,issuer,subject)`；index tenant/status |
| `role_assignments` | assignment_id, tenant, user_id FK, role_code, scope_type/id, permissions JSONB, status, valid_from/until, grant/revoke actor/reason, version | partial UNIQUE active `(user_id,role_code,scope_type,scope_id)`；index user/status/time |
| `user_sessions` | session_id, tenant, issuer, subject, idp_session_hash, token_id_hash, client_id, auth_time, acr, amr JSONB, device_hash, lifecycle timestamps, status, revoke data, version | UNIQUE session hash；index subject/status/expires |
| `step_up_challenges` | challenge_id/key, tenant, subject/session, action/resource, required_acr/amr, nonce_hash, status, attempts, expiry, verified/proof_hash/consumed, correlation, version | UNIQUE challenge_key/proof_hash；index status/expires |
| `service_identities` | id, tenant, issuer, subject, client_id, service_name, audiences/scopes JSONB, status, validity, last_seen, version | UNIQUE `(tenant,issuer,subject)` and `(tenant,client_id)` |
| `authorization_decisions` | id, decision_key, input_hash, tenant, actor/subject/session snapshot, action/resource, effect, roles/scopes/ownership/assurance, reasons/constraints JSONB, created/expires/correlation | UNIQUE `(decision_key,input_hash)`；index subject/resource/created |
| `identity_audit_records` | audit_id, tenant, action, actor/subject/resource refs, before/after hash, outcome, reason, trace/correlation, occurred_at, previous_hash, record_hash | append-only；index tenant/time/action |
| `outbox_events` | event_id, aggregate_type/id, event_type/version, payload, status, attempts, available/published times | index status/available_at |
| `processed_events` | consumer_name, event_id, event_type, processed_at, payload_hash | PK `(consumer_name,event_id)` |

issuer/subject 可检索但访问受限；subjectHash 用于事件。Email/display name 可加密并按 retention 清除。JSONB 只能用于可扩展集合，关键查询字段必须独立列。Flyway migration 仅前滚，生产禁止自动 destructive migration。

---

> Domain: `01-user-access-authentication`  
> Service: `user-access-authentication-service`  
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`  
> Status: Detailed LLD  
> Spec mapping: `SPEC-UA-002, SPEC-UA-008, SPEC-UA-031`
