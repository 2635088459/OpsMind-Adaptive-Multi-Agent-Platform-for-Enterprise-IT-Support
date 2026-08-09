# SPEC-TW-034 验收标准

## 功能验收

- 给定符合策略的请求，policy 允许后业务流程保持 Phase 01～08 原有行为。
- 给定违反策略的请求，系统在业务 mutation 前拒绝，并返回稳定错误合同。
- `audit.sensitive-read-recorded` 对应的审计/安全记录被写入或计量。

## 安全验收

- 拒绝响应不泄漏授权范围、检测规则、secret pattern 或内部策略细节。
- 日志、metric、trace attribute 均保持低基数且无 PII/secret。
- policy bypass 测试覆盖 controller、application service 和 internal consumer 入口。

## 回归验收

- Phase 01～08 golden path 不被破坏。
- 已有 idempotency、outbox 和 audit 事务边界保持不变。
