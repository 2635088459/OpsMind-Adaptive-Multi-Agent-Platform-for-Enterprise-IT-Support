# SPEC-TW-018 — 领域规则

| 当前状态 | 目标状态 | Transition ID | Reason Code |
|---|---|---|---|
| `IN_PROGRESS` | `IN_PROGRESS` | `SM-020` | `AUTO_APPROVAL_APPLIED` |

Auto-approved 必须保存 policyId、policyVersion、decisionId、actionId、riskLevel。缺少这些字段不得授权 execution。
