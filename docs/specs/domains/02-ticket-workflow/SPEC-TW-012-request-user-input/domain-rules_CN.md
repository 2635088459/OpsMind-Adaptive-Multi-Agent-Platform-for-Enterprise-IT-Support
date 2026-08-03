# SPEC-TW-012 — 领域规则

## 1. 状态转换

| 当前状态 | 目标状态 | Transition ID | Reason Code |
|---|---|---|---|
| `IN_PROGRESS` | `WAITING_FOR_USER` | `SM-014` | `USER_INPUT_REQUIRED` |

## 2. 不变量

- Ticket 当前必须为 `IN_PROGRESS`；
- Ticket 必须有 `current_support_user_id`；
- Ticket 必须有 `current_resolution_cycle_id`；
- 当前 Ticket 不得存在 open user input request；
- Prompt 必须是 requester-safe；
- 成功后保留负责人和当前 resolution cycle；
- 成功后 `waiting_for_requester_since = requested_at`；
- `approval_reference` 必须为 null 或不受影响，不能用 user input request 伪造 approval。

## 3. Resume Status

当前实现的 Ticket 持久化恢复目标固定为：

```text
IN_PROGRESS
```

如果 request 需要保存 workflow runtime resume hint，可用 `resume_status = IN_PROGRESS` 或 `resume_context` 保存，但客户端不能任意指定 workflow ID。

## 4. 安全

Prompt 视为外部可见内容，必须过滤 secret，并禁止 support/agent 诱导 requester 提交密码、MFA code、access token 或 private key。
