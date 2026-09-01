# SPEC-ARO-040 — Acceptance Criteria

目标：支撑 `确认/拒绝与限时同步等待`。

- 低风险 `confirm` 在工具真的在限时内完成时，真实完成一次工具请求，返回 `"done"`。
- 低风险 `confirm` 的工具没能及时完成时，返回 `"still-processing"`——从不是虚假的 `"done"`，也从不无限挂起。
- 高风险 `confirm` 真实在 `06-policy-approval-governance` 里创建一条 `ApprovalRequest`，永远返回 `"awaiting-approval"`。
- `decline` 产生零条 `tool_requests`/`approval_requests` 记录，直接对数据库验证。
- 对一个已经终态的 `actionId` 重复确认/拒绝，返回既有的真实状态，不触发新的副作用。
