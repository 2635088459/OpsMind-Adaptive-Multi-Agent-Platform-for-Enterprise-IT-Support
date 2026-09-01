# Employee Portal — 用例

> **Document ID:** LLD-EP-004
> **Domain:** `09-employee-portal`
> **状态:** Draft

---

## UC-EP-01 发起新会话

**Actor:** 已登录员工
**前置条件:** `UserSession` 处于 `AUTHENTICATED`
**主流程:**
1. 员工打开门户，看到空的对话区（或最近一次未结束的会话，见 UC-EP-06）
2. 选择/让 agent 自动判断服务分类（访问权限/登录/电脑/打印机/VPN/其他，仅作为展示分组，不是必填表单字段）
3. 输入第一条消息
**产出:** 一个新的 `Conversation`（conversationId 由后端签发，见 `05-api-contracts` §2.1）

## UC-EP-02 发送消息（可带附件）

**Actor:** 已登录员工
**前置条件:** 会话轮次状态机处于 `IDLE`
**主流程:**
1. 员工输入文字，可选择添加照片/文件（触发 §3.2 附件状态机）
2. 点击发送 → 进入 `SENDING` → `AWAITING_AGENT`
3. agent 返回：纯文本 / `ProposedAction` / `EscalationNotice` 三选一
**验收标准:**
- 附件未就绪时发送按钮禁用（BI-EP-002）
- 重复点击发送不产生两条重复消息（见 `09-concurrency-and-idempotency`）

## UC-EP-03 确认或拒绝一个自助处理方案

**Actor:** 已登录员工
**前置条件:** 会话轮次状态机处于 `AWAITING_CONFIRMATION`
**主流程:**
1. 员工阅读方案说明（BI-EP-007：必须看到完整说明，不能被截断）
2. 点击"确认，帮我处理" → 进入 `ACTION_EXECUTING` → agent 执行 → 状态卡逐步更新（如视觉稿里的"✓ 已解除旧设备配对"）
3. 执行完成后 agent 追加确认性提问（"解决了吗？"）
**替代流程:** 员工点击"先不用" → 直接回到 `IDLE`，不触发任何后端副作用
**错误流程:** 执行失败 → `ACTION_FAILED` → agent 自动提出转人工（进入 UC-EP-04 的创建工单路径），而不是让员工卡在一个失败态里

## UC-EP-04 agent 判断无权限，自动创建工单并通知

**Actor:** 系统（agent-runtime 编排）触发，员工被动接收
**前置条件:** agent 判断当前请求超出自己的执行权限/能力范围
**主流程:**
1. agent 消息里带 `EscalationNotice`（含真实 ticketId、原因、处理团队）
2. 前端在对话流里插入一条醒目提示（视觉稿里的"已创建工单 INC-2483，进展可以看右边"）
3. 工单状态面板出现/更新，开始展示真实状态机进展（见 UC-EP-05）
**验收标准:** ticketId 必须是 `02-ticket-workflow` 真实签发的，不允许前端自己生成一个临时占位 ID 后续再替换

## UC-EP-05 查看工单进展

**Actor:** 已登录员工
**前置条件:** 当前会话已关联一个真实 ticketId
**主流程:**
1. 面板通过 SSE（或轮询降级，见 `10-error-handling-and-reconciliation`）持续接收 `02-ticket-workflow` 的状态变化
2. 状态机步骤高亮当前所处阶段（NEW → TRIAGED → IN_PROGRESS → RESOLVED，与后端真实 `TicketStatus` 一一对应，不做前端自己的映射简化）
3. 到达 `RESOLVED` 时，面板提示员工确认解决（对应 `SPEC-TW-026-confirm-resolution`，真实存在的后端能力）
**验收标准:** 展示的状态永远是服务端最新值；断线重连后不能把面板"倒退"回旧状态（Last-Event-ID 保证顺序，见 shared baseline §4）

## UC-EP-06 返回门户，恢复上次会话

**Actor:** 已登录员工
**前置条件:** 该员工存在未关闭的 `Conversation`（或已升级但工单未 CLOSED）
**主流程:**
1. 打开门户时优先展示最近一次活跃/已升级的会话，而不是空白页
2. 若已升级，工单面板直接按当前真实状态渲染（不重放整个历史动画）
**非目标:** 本期不支持"多个并行会话列表"（视觉稿只展示单一当前会话），多会话历史列表留给后续 phase

## 待建后端能力清单（本 domain 的用例依赖，但不由本 domain 实现）

| 用例 | 依赖的新增后端能力 | 归属 domain |
|---|---|---|
| UC-EP-01/02/03 | 对话轮次端点（会话创建、发消息、确认方案） | 03-agent-runtime-orchestration |
| UC-EP-02（附件） | 附件/对象存储上传端点 | 待定（可能是新的共享能力，非某个现有 domain 天然拥有） |
| UC-EP-05 | 工单状态变化的 SSE 推送端点 | 02-ticket-workflow（当前只有 REST 读取，无推送） |

这张表是本 LLD 集里最重要的产出之一：它诚实列出"员工门户要真正好用，后端还差什么"，而不是假装这些能力已经存在。
