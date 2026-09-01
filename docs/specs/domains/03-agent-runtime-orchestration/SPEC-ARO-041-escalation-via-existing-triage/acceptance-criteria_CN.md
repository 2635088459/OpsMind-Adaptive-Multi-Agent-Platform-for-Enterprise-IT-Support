# SPEC-ARO-041 — Acceptance Criteria

目标：支撑 `借助既有分诊转人工`。

- 真实的分诊调用到达 `02-ticket-workflow`，工单真实被路由进一个真实的支持队列。
- 面向员工的 `escalation` 响应里的 `ticketId`/`assignedTeam`，与 09 号 domain 的 `EscalationNotice` 形状完全一致。
- 转人工之后，该工单真实出现在 `10-support-console` 的队列视图里，本工作流实例不再对它尝试任何进一步的自动化动作。
