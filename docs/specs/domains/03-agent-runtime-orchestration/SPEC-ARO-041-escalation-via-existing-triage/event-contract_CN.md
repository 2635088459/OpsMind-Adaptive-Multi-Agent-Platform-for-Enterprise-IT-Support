# SPEC-ARO-041 — Event Contract

目标：支撑 `借助既有分诊转人工`。

- 没有新事件。复用 `02-ticket-workflow` 自己已经真实存在的 `ticket.triaged` 事件，本 spec 不影响它。
- 本 spec 自己的外呼是一次同步 HTTP 请求，不是事件发布。
