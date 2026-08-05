# SPEC-TW-021 — 领域规则

| 当前状态 | 目标状态 | Transition ID | Reason Code |
|---|---|---|---|
| `EXECUTING` | `ESCALATED` | `SM-024` | `TOOL_RESULT_UNKNOWN` |

Unknown result 是安全边界。系统必须先核查外部状态，再决定重试、补偿、验证或人工处理。
