# API Contract — SPEC-EI-007

## API 影响

本 spec 可能新增或修改 07 API，但必须保持以下原则：

- 写 API 必须要求 authenticated actor/service identity、idempotency key 和 correlation id；
- 查询 API 默认返回 metadata、score summary 和 artifact reference，不默认返回 sensitive evidence；
- command API 必须区分 validation error、conflict、not found、permission denied、gate failed 和 dependency unavailable；
- API 响应必须包含 stable status、version、timestamps 和 trace/correlation 信息。

## 主要契约

- 与本 spec 相关的请求必须包含 source linkage 或等价 reference；
- 与评估结果相关的响应必须包含 dataset version、target version、grader version 和 evidence ref；
- 与 candidate/release 相关的响应必须包含 gate decision、approval reference 和 rollback eligibility；
- 所有 command API 必须支持幂等。
