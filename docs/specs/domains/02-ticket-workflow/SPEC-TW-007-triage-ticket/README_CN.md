# SPEC-TW-007 — Triage Ticket（工单分诊）

> 领域：Ticket Workflow  
> 服务：`ticket-workflow-service`  
> Phase：03 — 工单生命周期与负责人管理  
> 状态：可以进入实现  
> 前置条件：`SPEC-TW-001` ～ `SPEC-TW-006`

## 1. 目标

将一个已存在的 `OPEN` Ticket 转换为已经完成分类、优先级判断和队列路由、可进入分配流程的 `TRIAGED` Ticket。

这是一个 command-side vertical slice。命令成功时，Ticket、状态历史、Timeline、Audit Log、幂等记录和 Transactional Outbox 必须在同一个数据库事务中完成写入。

## 2. 业务结果

分诊前，Ticket 只是一条尚未分类的请求。分诊后，支持团队应能明确：

- 这是什么类型的问题；
- 问题有多紧急；
- 下一步由哪个支持队列负责；
- 谁在什么时间完成了分诊；
- 完全相同的命令是否已经处理过。

## 3. 范围内

- `POST /api/v1/tickets/{ticketId}/triage`；
- 选择分类和可选子分类；
- 选择优先级；
- 路由到支持队列；
- `OPEN → TRIAGED`；
- 操作者权限与队列权限；
- 通过 `If-Match` 实现乐观锁；
- 通过 `Idempotency-Key` 实现命令幂等；
- 写入状态历史、Timeline、Audit 和 Outbox；
- 发布 `ticket.triaged.v1`；
- 单元、集成、API 契约、事件契约和并发测试。

## 4. 范围外

- AI 或规则引擎自动分类；
- 自动计算优先级；
- 分配或认领 Ticket；
- SLA 计时和违约升级；
- 通知；
- 审批流程；
- 知识库推荐；
- Agent 工具调用或自动修复。

## 5. 必需文件

| 文件 | 用途 |
|---|---|
| `README_EN.md` / `README_CN.md` | 范围、结果和实现顺序 |
| `acceptance-criteria_EN.md` / `_CN.md` | 可执行行为与完成定义 |
| `api-contract_EN.md` / `_CN.md` | HTTP 请求、响应和错误契约 |
| `domain-rules_EN.md` / `_CN.md` | 聚合规则、权限和事务流程 |
| `persistence_EN.md` / `_CN.md` | Schema 变更与持久化不变量 |
| `event-contract_EN.md` / `_CN.md` | Timeline、Audit 和领域事件契约 |
| `test-plan_EN.md` / `_CN.md` | TDD 顺序与测试矩阵 |
| `openapi.yaml` | 机器可读的 HTTP 契约 |
| `asyncapi.yaml` | 机器可读的事件契约 |
| `V007__triage_ticket.sql` | PostgreSQL/Flyway 参考迁移 |
| `examples.http` | 成功与失败请求示例 |

## 6. 标准命令

```text
POST /api/v1/tickets/{ticketId}/triage
Authorization: Bearer <token>
If-Match: "7"
Idempotency-Key: 2df4faae-9862-4ee6-bca0-a3b8a3455aa0
X-Correlation-Id: 21ae628b-f15d-47d1-a937-1be0f85d4cd1
```

```json
{
  "categoryId": "11111111-1111-1111-1111-111111111111",
  "subcategoryId": "22222222-2222-2222-2222-222222222222",
  "priority": "HIGH",
  "supportQueueId": "33333333-3333-3333-3333-333333333333",
  "reason": "VPN access failure affects the requester's scheduled shift."
}
```

## 7. 成功不变量

- 命令执行前状态必须是 `OPEN`；
- 命令执行后状态必须是 `TRIAGED`；
- 分类、优先级和队列必须有效且处于 active 状态；
- 如果提供子分类，它必须属于所选分类；
- 操作者身份必须来自认证信息，不能来自请求体；
- 操作者必须有权将 Ticket 分诊到目标队列；
- Ticket 版本号只增加一次；
- 提交一条状态历史、一条 Timeline、一条 Audit 和一条 Outbox Event；
- 事务回滚后，上述写入均不可见；
- 相同幂等键和相同请求必须返回已保存的结果，不能再次修改；
- 相同幂等键对应不同请求时必须拒绝。

## 8. 实现顺序

1. 固定验收标准和契约。
2. 先编写失败的领域测试。
3. 添加数据库迁移和 Repository Mapping。
4. 实现 `TriageTicketCommand` 与 Handler。
5. 添加权限、分类和队列验证。
6. 原子写入历史、Timeline、Audit、幂等记录和 Outbox。
7. 添加 API、集成、契约、回滚和并发测试。
8. 验证 Metrics、结构化日志和文档。

在本目录全部验收标准通过前，不应开始 `SPEC-TW-008`。

