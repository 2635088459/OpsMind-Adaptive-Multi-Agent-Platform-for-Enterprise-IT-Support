# Domain Rules — SPEC-TG-028

## 必须遵守

- 工具执行必须经过 Gateway；状态必须与 Ticket/Workflow 分离；外部副作用必须幂等、可审计、可恢复；事件发布必须走 outbox；事件消费必须 processed-event 去重。
- `tool.completed.v1` 不代表 Ticket resolved，也不代表 Workflow completed。
- Connector capability 不是权限；执行前仍需 actor、scope、policy、credential binding 校验。
- mutation connector 必须有 operation key。

## 禁止

- Ticket/Workflow state 直接修改；Agent 直连 Tool；secret/raw output 泄漏；绕过 Policy/Approval；跨 domain 分布式事务。
- 把 connector raw output 直接写入 Memory Knowledge；
- 在数据库事务内执行外部 connector 调用；
- 用 generic failed 抹平 policy denied、approval denied、timeout、partial side effect。
