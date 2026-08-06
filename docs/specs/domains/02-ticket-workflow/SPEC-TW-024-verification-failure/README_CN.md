# SPEC-TW-024 — Verification Failure（验证失败）

## 1. 目标

消费 trusted verification failure result，区分 retryable、unsafe、limit reached 和 pipeline failure，并将 Ticket 安全恢复到 `IN_PROGRESS`、进入 `ESCALATED` 或 `FAILED`。

第三次失败或 unsafe result 必须升级。
