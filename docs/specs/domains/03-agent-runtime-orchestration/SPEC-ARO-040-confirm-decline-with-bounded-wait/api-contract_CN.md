# SPEC-ARO-040 — API Contract

目标：支撑 `确认/拒绝与限时同步等待`。

- `POST /api/v1/conversations/{conversationId}/actions/{actionId}/confirm`，需要 `Idempotency-Key`。
- `POST /api/v1/conversations/{conversationId}/actions/{actionId}/decline`，需要 `Idempotency-Key`。
- `confirm` 的响应：`{outcome: "done" | "still-processing" | "awaiting-approval", ...}`——一个明确的结果判别字段，对应 09 号 domain 自己 LLD/product-vision memory 里已经记录的那个细微差别（视觉稿里"立即 ✓ 已完成"是常见情况，不是唯一的契约形状）。
- `decline` 的响应：`{outcome: "declined"}`。
