# SPEC-TW-023 — 领域规则

| 当前状态 | 目标状态 | Transition ID | Reason Code |
|---|---|---|---|
| `VERIFYING` | `VERIFYING` | `SM-026` | `VERIFICATION_SUCCEEDED` |

成功 verification 必须是当前 active attempt 的终态。Evidence 必须可追踪但不得包含 secret 或完整日志。
