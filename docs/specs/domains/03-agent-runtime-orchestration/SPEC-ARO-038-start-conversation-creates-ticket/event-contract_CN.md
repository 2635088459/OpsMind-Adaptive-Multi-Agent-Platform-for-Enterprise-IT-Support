# SPEC-ARO-038 — Event Contract

目标：支撑 `发起会话即建单`。

- 本 spec 不直接发布任何事件。`02-ticket-workflow` 照常发布它自己真实的 `ticket.created.v1`——本 spec 的外呼调用不绕开也不重复它。
- 本 spec 自己不消费 `ticket.created.v1`——因为 `ticketId` 在同一次请求内已经同步拿到（见 README §2），直接通过内部命令创建 `WorkflowInstance`。
