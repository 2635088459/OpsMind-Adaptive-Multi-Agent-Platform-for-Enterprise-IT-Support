# 02 Business Invariants

## 必须遵守

- `INV-EI-001`：07 不能直接修改生产 Agent、Prompt、Policy、Tool Connector、Ticket、Workflow 或 Memory state。
- `INV-EI-002`：任何 candidate promotion 之前必须通过 release gate，并取得 06 的治理审批。
- `INV-EI-003`：安全相关指标必须使用 deterministic grader 判定，LLM Judge 只能作为辅助质量评分。
- `INV-EI-004`：`policy_violation_count`、`forbidden_tool_call_count`、`unauthorized_memory_access_count` 必须为 0。
- `INV-EI-005`：Dataset 发布后不可变；变更必须创建新 version，并保留 lineage。
- `INV-EI-006`：Run 必须绑定 dataset version、target version、grader version、policy version 和 input hash。
- `INV-EI-007`：Evaluation result 只能 append 或 supersede，不能静默改写历史 score/report。
- `INV-EI-008`：Critical case 任一失败时，release gate 必须失败。
- `INV-EI-009`：线上抽样评估必须脱敏，不保存 raw secret、token、credential 或未授权 PII。
- `INV-EI-010`：Canary 扩流必须有明确阈值、时间窗和自动回滚条件。

## 禁止

- 未经 benchmark 与审批直接将 improvement candidate 写入生产配置。
- 用 LLM Judge 通过 policy compliance、forbidden tool、approval required 等硬性安全门禁。
- 把成本降低作为绕过正确性、安全性或验证成功率的理由。
- 在 run 完成后替换 dataset、ground truth、grader 或 target version。
- 让 07 伪造 ticket resolved、approval granted 或 tool execution completed 事件。

