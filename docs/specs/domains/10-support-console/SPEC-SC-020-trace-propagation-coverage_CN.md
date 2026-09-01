# SPEC-SC-020 — Trace Propagation Coverage（追踪传播覆盖）

> Domain: `10-support-console` | Phase: 08 — 安全与发布加固 | 状态：Spec Planning

## 1. Spec 身份
`SPEC-SC-020`，domain 10 roadmap 的最后一个 spec——收尾本 domain 的发布就绪阶段，并与 `SPEC-EP-023` 一起，完成两个前端 domain 整个 43 个 spec 的 Feature Spec roadmap。

## 2. 目标
确认支持控制台任何地方发起的每个网络调用都传播了 `traceparent` header，使一个工单的完整生命周期——AI 处理、转人工、人工分诊/指派/审批——能跨越它触及的所有后端 domain 端到端追踪，匹配已与用户确认过的可观测性视觉。

## 3. 设计依据
`12-observability-and-audit`（全部章节）；之前每个 spec 自己的 §15 可观测性章节；SPEC-SC-014（其本身就消费这同一套追踪基础设施）。

## 4. Actor
不适用——一个工程审计活动。

## 5. 范围
横切审查 SPEC-SC-001 到 SPEC-SC-019 每个 fetch/EventSource 调用点，确认 trace-context 传播情况——特别关注 SPEC-SC-006 的 3 个并发调用，理想情况下应为整个聚合操作共享一个父 span。

## 6. 非目标
OTel collector/后端追踪基础设施本身（属于 `08-observability-platform`，已完全关闭）。

## 7. 前置条件
之前所有 domain 10 的 spec 已实现。

## 8. 输入
测试/开发构建中观察到的实际运行时请求头。

## 9. 详细行为
对每个网络调用点，确认附带了一个有效的 W3C `traceparent` header——新根 span 现场生成，或从已有 span 传播；特别针对 SPEC-SC-006，确认 3 个并发调用共享一个公共父 span，而不是 3 条互不相连的 trace。

## 10. 交互状态迁移
不适用。

## 11. 业务不变量
本应用中没有任何网络调用是不可追踪的——与 `SPEC-EP-023` 相同的不变量。

## 12. 幂等策略
不适用。

## 13. 消费/依赖的契约
domain 10 中引用的所有契约，针对这一项属性一并重新审查。

## 14. 安全
不适用（trace header 按设计不携带任何敏感数据）。

## 15. 可观测性
本 spec 的全部内容都是可观测性关切——产出的成果物是一份确认全覆盖的审计报告，加一次人工验证：完整的工单生命周期 trace（通过 domain 09 的对话创建 → 转人工 → 在 domain 10 中分诊/指派/审批）在真实可观测性平台中能作为一条连贯的 trace 被追踪。

## 16. 错误场景
任何发现缺失追踪传播的调用点都是一个待修复项，在本 spec 关闭前修复。

## 17. 验收场景
一个跨越两个前端应用的完整工单生命周期，在真实 OTel 后端中产生一条连续、可跟随的 trace。

## 18. 先写测试
对调用点的静态审计，加一个断言 trace-context 连续性的集成风格测试，包括 SPEC-SC-006 的共享父 span 要求。

## 19. 完成定义
整个支持控制台代码库中零未追踪的调用点；这收尾了 domain 10 的 Phase 08 及其整个 Feature Spec roadmap（SPEC-SC-001 到 SPEC-SC-020），并与 `SPEC-EP-023` 一起，完成了 domain 09 和 10 全部 43 个 Feature Spec。
