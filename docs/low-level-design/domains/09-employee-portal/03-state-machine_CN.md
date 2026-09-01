# Employee Portal — 交互状态机

> **Document ID:** LLD-EP-003
> **Domain:** `09-employee-portal`
> **状态:** Draft

---

## 说明

后端 domain 的状态机描述的是**领域对象**的生命周期（Ticket 从 NEW 到 CLOSED）。这里描述的是**交互本身**的状态机——一次对话轮次、一个附件、一次登录会话各自怎么流转。这是前端 domain 特有的东西，后端的 14 篇模板里没有直接对应物，但沿用同一个文档编号位置，保持两套 domain 结构对称。

## 3.1 对话轮次状态机（Turn State Machine）

```text
IDLE
  → (用户发送消息) → SENDING
SENDING
  → (请求已达服务端) → AWAITING_AGENT      // "正在分析" thinking 态
  → (网络失败) → SEND_FAILED → (重试) → SENDING
AWAITING_AGENT
  → (agent 返回纯文本回复) → IDLE
  → (agent 返回 ProposedAction) → AWAITING_CONFIRMATION
  → (agent 返回 EscalationNotice) → ESCALATED
  → (超时/5xx) → AGENT_UNAVAILABLE
AWAITING_CONFIRMATION
  → (用户点击确认) → ACTION_EXECUTING
  → (用户点击拒绝) → IDLE
ACTION_EXECUTING
  → (执行成功) → IDLE   // agent 消息追加"已完成"状态卡
  → (执行失败) → ACTION_FAILED → (agent 自动降级为 ESCALATED 或允许用户重试)
ESCALATED
  → (工单已创建，工单面板开始展示) → IDLE   // 对话本身回到可继续输入的状态，工单面板独立生命周期
AGENT_UNAVAILABLE
  → (见 10-error-handling-and-reconciliation 的降级路径) → ESCALATED（走直连 ticket-workflow 的兜底创建单）
```

关键规则：`AWAITING_CONFIRMATION` 和 `ACTION_EXECUTING` 期间，输入框仍然可以打字（不阻塞用户问下一个问题），但**不能对同一条 ProposedAction 重复触发确认**——按钮点击后立即禁用，等服务端响应或超时。

## 3.2 附件状态机

```text
(未选择)
  → (用户选择文件) → VALIDATING     // 前端先校验大小/类型
VALIDATING
  → (通过) → UPLOADING
  → (不通过) → REJECTED（提示原因，不进入上传）
UPLOADING
  → (成功) → READY
  → (失败) → FAILED → (用户可重试) → UPLOADING
READY
  → (随消息一起发送) → 附着到 Message，脱离本状态机的管理
  → (用户移除) → (回到未选择)
```

对应 BI-EP-002：只有 `READY` 状态的附件允许出现在待发送消息里。

## 3.3 会话/登录状态机

```text
UNAUTHENTICATED
  → (发起 OIDC 登录) → LOGIN_IN_PROGRESS
LOGIN_IN_PROGRESS
  → (真实 Authorization Code + PKCE 回调成功) → AUTHENTICATED
  → (失败) → UNAUTHENTICATED（提示错误）
AUTHENTICATED
  → (access token 临近过期) → TOKEN_REFRESHING
  → (被撤销/refresh 失败) → SESSION_EXPIRED
TOKEN_REFRESHING
  → (成功，静默完成) → AUTHENTICATED
  → (失败) → SESSION_EXPIRED
SESSION_EXPIRED
  → (触发 BI-EP-006：草稿保存到本地) → UNAUTHENTICATED
  → (重新登录成功，恢复草稿) → AUTHENTICATED
```

这套状态机复用 `01-user-access-authentication` 已经现场验证过的真实 Authorization Code + PKCE 流程（见 `project-level-integration-verification` memory）——本 domain 不重新设计登录机制，只描述前端如何响应它的状态变化。

## 3.4 工单状态面板（独立于对话轮次）

工单面板不是本 domain 的状态机——它只是把 `02-ticket-workflow` 真实状态机（NEW → TRIAGED → ASSIGNED → IN_PROGRESS → WAITING_FOR_APPROVAL/WAITING_FOR_USER → RESOLVED → CLOSED）通过只读投影渲染出来。前端唯一自己维护的状态是**连接状态**：

```text
CONNECTING → LIVE（SSE 已连上，或轮询已开始）
LIVE → RECONNECTING（连接掉线，走 Last-Event-ID 续传，见 shared baseline §4）
RECONNECTING → LIVE / STALE（多次重试失败，提示"进展可能不是最新的，点击刷新"）
```
