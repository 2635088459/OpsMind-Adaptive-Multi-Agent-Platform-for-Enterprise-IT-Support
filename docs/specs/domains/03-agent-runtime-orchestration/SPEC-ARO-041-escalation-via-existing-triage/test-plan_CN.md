# SPEC-ARO-041 — Test Plan

目标：支撑 `借助既有分诊转人工`。

- 针对真实 `02-ticket-workflow` 分诊端点的集成测试（docker-compose 栈）。
- 端到端测试：发起一次会话，发一条真的需要转人工的消息，确认之后工单正确出现在 `10-support-console` 的队列里。
- 失败测试：转人工过程中 `ticket-workflow-service` 不可用——工作流实例不会悄悄进入一个虚假的终态，失败被明确暴露且可重试。
