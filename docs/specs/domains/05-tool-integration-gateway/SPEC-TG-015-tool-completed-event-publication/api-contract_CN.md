# API Contract — SPEC-TG-015

## API 影响

本 spec 可能新增或修改 Tool Gateway API，但必须保持以下原则：

- Runtime-facing API 只暴露 capability、request、status 和 redacted result；
- Admin-facing API 必须有 RBAC、audit reason 和 correlation id；
- Raw output 或 credential 相关 API 默认不可被 Agent/Runtime 调用；
- 所有命令 API 必须支持 idempotency key 或版本保护。

## 主要契约

- 输入必须包含 `correlationId` 或可由 Gateway 创建并回传；
- 响应不得包含 secret、vault ref、未脱敏 raw output；
- 错误必须使用稳定 error code；
- conflict、denied、timeout、uncertain outcome 必须区分。
