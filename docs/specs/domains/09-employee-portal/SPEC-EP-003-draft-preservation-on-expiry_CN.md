# SPEC-EP-003 — Draft Preservation on Expiry（过期时的草稿保存）

> Domain: `09-employee-portal` | Phase: 01 — 登录与会话 | 状态：Spec Planning

## 1. Spec 身份
`SPEC-EP-003`，BI-EP-006 的直接实现。

## 2. 目标
会话过期打断正在输入的消息时，已打字但未发送的文本（以及待处理的附件引用）被写入本地存储，重新登录成功后恢复——从不悄悄丢失。

## 3. 设计依据
`02-business-invariants` BI-EP-006；`07-data-model` §2.2（`draft:{conversationId}`）；`10-error-handling-and-reconciliation` §2.5。

## 4. Actor
会话在交互中途过期的已登录员工。

## 5. 范围
在 401/`SESSION_EXPIRED` 迁移时把草稿写入 `localStorage`；重新登录后恢复；同一设备上按账号隔离键（`subject` 前缀，遵循 `07-data-model` §4）。

## 6. 非目标
不尝试自动重发被打断的请求——重发是否安全交给用户自己判断（呼应 `10-error-handling-and-reconciliation` §2.5）。

## 7. 前置条件
正在编写或刚提交一条消息时收到 401。

## 8. 输入
当前输入框文本 + 任何 `READY`/`uploading` 的附件引用。

## 9. 详细行为
收到 401 时：同步通过 `localStorage.setItem` 写入 `draft:{subject}:{conversationId}`（不是异步 IndexedDB 写入，遵循 `08-transaction-and-outbox` §3）→ 提示重新登录 → 成功后把草稿读回输入框。

## 10. 交互状态迁移
搭在 SPEC-EP-002 的 `SESSION_EXPIRED` 迁移之上；不新增自己的状态。

## 11. 业务不变量
BI-EP-006（本 spec 存在的直接原因）。

## 12. 幂等策略
重复恢复同一份草稿（例如重复的重新登录事件）是幂等的——草稿键只是被覆盖/读取，不产生重复。

## 13. 消费/依赖的契约
无新增——依赖 SPEC-EP-001/002 的会话机制。

## 14. 安全
草稿键按 `subject` 加前缀，防止同一设备/浏览器 profile 上跨账号泄露（`07-data-model` §4）。

## 15. 可观测性
该路径触发频率的指标列为未来仪表盘的输入项（`12-observability-and-audit` §4），不是本 spec 完成定义的必需项。

## 16. 错误场景
`localStorage` 写入失败（例如配额超限、隐私浏览模式）——员工依然会看到重新登录提示；这种罕见情况下丢失草稿是可接受的降级，不会被悄悄掩盖成"成功"。

## 17. 验收场景
一条已打字但未发送的消息，在模拟的会话过期后依然存活，重新登录后重新出现在输入框里。

## 18. 先写测试
模拟交互中途 401 的组件测试，断言草稿被写入并恢复；同一浏览器 profile 下的跨账号隔离测试。

## 19. 完成定义
在真实的会话过期/重新登录循环中，没有任何已打字但未发送的草稿会丢失——由自动化测试验证，不只是人工观察。
