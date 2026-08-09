# Phase 09 — Security, Audit and Operational Hardening Slice

> Domain：Ticket Workflow
>
> Service：`ticket-workflow-service`
>
> Phase：09
>
> Specs：`SPEC-TW-033` ～ `SPEC-TW-036`
>
> 前置条件：Phase 01～08 已实现并通过验收
>
> 文档状态：Implementation Plan

## 1. Phase 目标

Phase 09 将前面每个 vertical slice 已经接入的基础 Security、Audit、Idempotency 和 Observability 能力集中强化到冻结 LLD 要求。

它不是第一次加入安全能力，也不是补救前面完全没有鉴权/审计的业务代码；它负责把生产前必须稳定的横切边界补齐、收紧并可验证。

核心目标：

```text
Security Baseline -> Hardened Authorization
Audit Trail -> Sensitive Read Audit
Free-text Input -> Secret Detection
Normal Auth -> Step-up Authentication
```

## 2. 设计边界

- Phase 09 不新增 Ticket 生命周期主状态；
- Phase 09 不改变 Phase 01～08 的状态机业务语义；
- support queue authorization 只能收紧访问，不得扩大可见范围；
- sensitive read audit 是读取侧保护，不得被普通 timeline/query 绕过；
- secret detection 只阻止敏感文本进入 Ticket message/reason/audit payload，不负责外部 secret rotation；
- step-up authentication 只用于高风险动作，不替代基础 JWT/OAuth2 鉴权；
- telemetry 必须低基数、无 PII、无 secret；
- 所有拒绝路径必须可观测，但不得泄漏授权策略细节。

## 3. Phase 09 Specs

| 顺序 | SPEC | 名称 | 核心职责 |
|---|---|---|---|
| 1 | `SPEC-TW-033` | Support Queue Authorization | 收紧 support queue 范围授权、过滤和命令准入 |
| 2 | `SPEC-TW-034` | Sensitive Read Audit | 对敏感读取强制审计和 fail-closed |
| 3 | `SPEC-TW-035` | Secret Detection | 拦截 message/reason/free-text 中的 secret-like 内容 |
| 4 | `SPEC-TW-036` | Step-up Authentication | 对高风险 ticket command 强制 step-up auth |

## 4. 横切覆盖

| 能力 | 覆盖范围 |
|---|---|
| Authorization | Ticket read、queue query、assignment、escalation、approval、admin action |
| Audit | sensitive read、business command、authorization denied、policy decision |
| Secret Detection | create/update message、request-user-input、approval/rejection reason、escalation/cancel/reopen reason |
| Step-up Auth | cancel、close、reopen closed ticket、escalate、auto-approved high-risk policy |
| Observability | metrics、structured log、trace attributes、alert trigger |

## 5. 事件与审计

Phase 09 通常不发布新的业务生命周期事件；它主要强化：

```text
audit.sensitive-read-recorded
audit.authorization-denied-recorded
security.secret-detected
security.step-up-required
security.step-up-verified
```

这些可以先落在本服务 audit/telemetry 内部模型；如果未来拆分 Audit/Security domain，再通过 outbox 或专门事件桥接。

## 6. 退出条件

- `SPEC-TW-033`～`SPEC-TW-036` 文档、代码、contract 和测试闭环；
- support queue scope 对 query 和 command 均生效；
- sensitive read audit fail-closed；
- secret-like payload 不进入业务表、audit free-text 或 outbox payload；
- high-risk command 缺少 step-up proof 时拒绝执行；
- 所有拒绝路径有低基数 metric 和安全日志；
- 不破坏 Phase 01～08 golden path。
