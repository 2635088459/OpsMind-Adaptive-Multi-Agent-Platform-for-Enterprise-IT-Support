# API Contract — SPEC-PG-030

## API 影响

本 spec 可能新增或修改 06 API，但必须保持以下原则：

- Decision API 返回 governance facts，不执行业务副作用；
- Approval API 必须要求 authenticated actor、idempotency key、reason、correlation id；
- Admin Policy API 必须要求 reviewer/publisher 职责分离；
- Audit API 默认只返回 metadata/hash，不返回敏感原始 input。

## 主要契约

- 输入必须包含 sourceDomain/sourceRequestId 或等价 linkage；
- 响应必须包含 stable status/effect/risk/reason code；
- conflict、denied、expired、cancelled、evaluation failed 必须区分；
- 所有 command API 必须支持幂等。
