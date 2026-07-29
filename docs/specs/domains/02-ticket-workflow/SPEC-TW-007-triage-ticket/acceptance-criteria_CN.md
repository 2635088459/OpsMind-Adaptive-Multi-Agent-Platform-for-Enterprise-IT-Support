# SPEC-TW-007 — 验收标准

## AC-01 — 成功分诊

**Given** Ticket 已存在且状态为 `OPEN`，分类有效，子分类可选且有效，支持队列有效，操作者有权限，幂等键未使用，`If-Match` 版本一致  
**When** 操作者提交分诊命令  
**Then** 服务返回 `200 OK`，设置分诊字段，将状态改为 `TRIAGED`，增加版本号并返回新 `ETag`，同时原子写入状态历史、Timeline、Audit、幂等结果和 `ticket.triaged.v1`。

## AC-02 — 可选子分类

**Given** 分类有效且未提供 `subcategoryId`  
**When** 分诊成功  
**Then** `subcategoryId` 保持 `null`。

## AC-03 — 分类校验

分类不存在或未启用时返回 `422 TRIAGE_CATEGORY_INVALID`，不能提交任何修改。

## AC-04 — 子分类关系

子分类不存在、未启用或不属于所选分类时，返回 `422 TRIAGE_SUBCATEGORY_INVALID`。

## AC-05 — 优先级校验

只接受 `LOW`、`MEDIUM`、`HIGH`、`CRITICAL`。其他值返回 `400 VALIDATION_ERROR`。

## AC-06 — 队列校验

支持队列不存在或未启用时返回 `422 SUPPORT_QUEUE_INVALID`。

## AC-07 — 队列权限

已认证但没有目标队列分诊权限的操作者收到 `403 QUEUE_ACCESS_DENIED`。Requester 始终收到 `403 TRIAGE_NOT_ALLOWED`。

## AC-08 — 状态保护

只有 `OPEN` 可以被分诊。其他任何状态返回 `409 INVALID_TICKET_STATE`，并包含 `currentStatus` 与 `requiredStatus=OPEN`。

## AC-09 — 不存在与租户隔离

未知 Ticket 或其他租户的 Ticket 返回 `404 TICKET_NOT_FOUND`。响应不能泄露跨租户 Ticket 是否真实存在。

## AC-10 — 乐观锁

缺少 `If-Match` 返回 `428 PRECONDITION_REQUIRED`。版本过期返回 `412 VERSION_CONFLICT`，带回当前 `ETag`，且不提交任何修改。

## AC-11 — 幂等重放

同一操作者、路由、幂等键和标准化请求哈希必须返回之前保存的 `200` 响应及 `ETag`，不能再次创建历史、Timeline、Audit 或 Outbox 记录。

## AC-12 — 幂等冲突

相同幂等键对应不同请求哈希时返回 `409 IDEMPOTENCY_KEY_REUSED`。

## AC-13 — 操作者身份完整性

`triagedBy` 必须来自 Access Token 或 Service Identity。请求体包含任何 Actor 字段时，应将其作为未知属性拒绝。

## AC-14 — 原子回滚

任一必要持久化操作失败时，Ticket 与所有附属记录必须保持不变，且不能存在可发布的 Outbox 记录。

## AC-15 — 事件与审计安全

事件、日志和 Audit Payload 只能包含标识符及获准的业务字段，不能包含 Access Token、凭证或不受限制的 Requester 消息正文。

## AC-16 — 可观测性

每次尝试都记录带有限结果标签的 `ticket_triage_total` 和命令耗时。结构化日志包含 `ticketId`、`actorId`、`fromStatus`、`toStatus`、`result`、`errorCode` 和 `correlationId`。

## 完成定义

- 所有 AC 都有自动化测试；
- `openapi.yaml` 与实现的契约测试一致；
- `asyncapi.yaml` 与事件序列化契约测试一致；
- 数据库迁移可以在空的受支持数据库及 Phase 02 Schema 上成功执行；
- 回滚测试和双写者并发测试通过；
- 不存在 Critical/High 安全问题；
- 文档与代码使用完全一致的字段名和错误代码。

