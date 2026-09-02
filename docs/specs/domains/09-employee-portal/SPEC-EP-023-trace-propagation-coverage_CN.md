# SPEC-EP-023 — Trace Propagation Coverage（追踪传播覆盖）

> Domain: `09-employee-portal` | Phase: 08 — 安全与发布加固 | 状态：Implemented

## 1. Spec 身份
`SPEC-EP-023`，domain 09 roadmap 的最后一个 spec——收尾本 domain 的发布就绪阶段。

## 2. 目标
确认员工门户任何地方发起的每个网络调用都真正传播了 `traceparent` header，使一次对话的请求能够跨 `agent-runtime-service`、`ticket-workflow-service` 和 OTel collector 端到端追踪，遵循已与用户确认过的可观测性视觉（Agent Observability 视觉稿）。

## 3. 设计依据
`12-observability-and-audit`（全部章节）；之前每个 spec 自己的 §15 可观测性章节。

## 4. Actor
不适用——一个工程审计活动。

## 5. 范围
横切审查 SPEC-EP-001 到 SPEC-EP-022 每个 fetch/EventSource 调用点，确认 trace-context 传播情况。

## 6. 非目标
OTel collector/后端追踪基础设施本身（属于 `08-observability-platform`，已完全关闭）。

## 7. 前置条件
之前所有 domain 09 的 spec 已实现。

## 8. 输入
测试/开发构建中观察到的实际运行时请求头。

## 9. 详细行为
对每个网络调用点，确认附带了一个有效的 W3C `traceparent` header——对于一个新的根 span 现场生成，对于属于一个已有更大操作一部分的调用（例如已打开对话内的一次消息发送）则从现有 span 传播。

## 10. 交互状态迁移
不适用。

## 11. 业务不变量
一条新的横切不变量：本应用中没有任何网络调用是不可追踪的。

## 12. 幂等策略
不适用。

## 13. 消费/依赖的契约
domain 09 中引用的所有契约，针对这一项属性一并重新审查。

## 14. 安全
不适用（trace header 按设计不携带任何敏感数据，遵循 `08-observability-platform` 自己的约定）。

## 15. 可观测性
本 spec 的全部内容都是可观测性关切——产出的成果物是一份确认全覆盖的审计报告。

## 16. 错误场景
任何发现缺失追踪传播的调用点都是一个待修复项，在本 spec 关闭前修复。

## 17. 验收场景
一个完整的对话流程（创建 → 发送 → 确认 → 转人工）产生一条连续、可跟随的 trace，跨越前端和后端 span。

## 18. 先写测试
对调用点的静态审计，加一个断言完整对话流程中 trace-context 连续性的集成风格测试（真实后端端点存在前，用 MSW mock 断言 header 存在性）。

## 19. 完成定义
整个员工门户代码库中零未追踪的调用点；这收尾了 domain 09 的 Phase 08 及其整个 Feature Spec roadmap（SPEC-EP-001 到 SPEC-EP-023，各自随其自身收尾而实现）。
