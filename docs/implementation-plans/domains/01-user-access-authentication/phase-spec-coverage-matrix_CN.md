# 01 User Access And Authentication Phase / Spec Coverage Matrix

## 目标

确认 10 个 phase、36 个 spec 覆盖 LLD 14 个切面，并闭环体验层、Ticket Workflow、Policy Governance 与内部服务身份。

## Phase / Spec 总览

| Phase | Specs | 闭环目标 |
|---|---|---|
| 00 工程基础 | `SPEC-UA-001` ～ `SPEC-UA-003` | 闭环 工程基础。 |
| 01 OIDC 与 Token 信任 | `SPEC-UA-004` ～ `SPEC-UA-007` | 闭环 OIDC 与 Token 信任。 |
| 02 用户与会话生命周期 | `SPEC-UA-008` ～ `SPEC-UA-010` | 闭环 用户与会话生命周期。 |
| 03 授权、RBAC 与 Scope | `SPEC-UA-011` ～ `SPEC-UA-015` | 闭环 授权、RBAC 与 Scope。 |
| 04 认证强度与 Step-Up | `SPEC-UA-016` ～ `SPEC-UA-019` | 闭环 认证强度与 Step-Up。 |
| 05 体验层访问契约 | `SPEC-UA-020` ～ `SPEC-UA-024` | 闭环 体验层访问契约。 |
| 06 跨域身份契约 | `SPEC-UA-025` ～ `SPEC-UA-028` | 闭环 跨域身份契约。 |
| 07 安全、可观测性与隐私 | `SPEC-UA-029` ～ `SPEC-UA-031` | 闭环 安全、可观测性与隐私。 |
| 08 失败恢复与降级模式 | `SPEC-UA-032` ～ `SPEC-UA-034` | 闭环 失败恢复与降级模式。 |
| 09 最终验证与发布 | `SPEC-UA-035` ～ `SPEC-UA-036` | 闭环 最终验证与发布。 |

## LLD 覆盖

| LLD Section | Specs |
|---|---|
| 01-domain-model | `SPEC-UA-007`, `SPEC-UA-008`, `SPEC-UA-011`, `SPEC-UA-016` |
| 02-business-invariants | `SPEC-UA-001`, `SPEC-UA-011`, `SPEC-UA-013`, `SPEC-UA-015` |
| 03-state-machine | `SPEC-UA-002`, `SPEC-UA-009`, `SPEC-UA-012`, `SPEC-UA-017` |
| 04-use-cases | `SPEC-UA-005`, `SPEC-UA-009`, `SPEC-UA-012`, `SPEC-UA-015`, `SPEC-UA-017` |
| 05-api-contracts | `SPEC-UA-004`, `SPEC-UA-005`, `SPEC-UA-007`, `SPEC-UA-010`, `SPEC-UA-014`, `SPEC-UA-018`, `SPEC-UA-020`, `SPEC-UA-021`, `SPEC-UA-022`, `SPEC-UA-023`, `SPEC-UA-024`, `SPEC-UA-025`, `SPEC-UA-026`, `SPEC-UA-027` |
| 06-event-contracts | `SPEC-UA-021`, `SPEC-UA-024`, `SPEC-UA-025`, `SPEC-UA-026`, `SPEC-UA-028`, `SPEC-UA-029` |
| 07-data-model | `SPEC-UA-002`, `SPEC-UA-008`, `SPEC-UA-031` |
| 08-transaction-and-outbox | `SPEC-UA-003`, `SPEC-UA-028` |
| 09-concurrency-and-idempotency | `SPEC-UA-003`, `SPEC-UA-033`, `SPEC-UA-034` |
| 10-failure-handling | `SPEC-UA-006`, `SPEC-UA-019`, `SPEC-UA-032`, `SPEC-UA-033` |
| 11-security | `SPEC-UA-004`, `SPEC-UA-006`, `SPEC-UA-010`, `SPEC-UA-013`, `SPEC-UA-014`, `SPEC-UA-016`, `SPEC-UA-018`, `SPEC-UA-019`, `SPEC-UA-023`, `SPEC-UA-027`, `SPEC-UA-031`, `SPEC-UA-032`, `SPEC-UA-034` |
| 12-observability | `SPEC-UA-029`, `SPEC-UA-030` |
| 13-package-and-class-design | `SPEC-UA-001` |
| 14-testing-strategy | `SPEC-UA-020`, `SPEC-UA-022`, `SPEC-UA-035`, `SPEC-UA-036` |

## 最终完成标准

- 36 specs have bilingual planning, contracts, persistence rules, acceptance criteria, tests, and traceability.
- Keycloak remains the credential/OIDC/MFA authority; domain 01 stores no credential material.
- Browser and workload identity flows are both contract-tested.
- 01/02/06 authentication, authorization, and step-up E2E paths pass.
- Audit, outbox, recovery, degraded mode, privacy, and release-readiness evidence is complete.
