# SPEC-SC-014 — Trace Waterfall Preview（Trace 瀑布图预览）

> Domain: `10-support-console` | Phase: 06 — 可观测性界面 | 状态：Spec Planning

## 1. Spec 身份
`SPEC-SC-014`，实现 `UC-SC-07` 的 OTel 一半，匹配已被用户批准的 "Agent Observability" 视觉稿。

## 2. 目标
为一个工单的 agent 处理 trace 嵌入一个瀑布图视图（span 层级、耗时），数据来源于 `08-observability-platform`（已完全关闭，全部 36 个 spec）建成的真实 OpenTelemetry 后端。

## 3. 设计依据
`01-domain-model` §"TraceWaterfall"；`04-use-cases` UC-SC-07；Agent Observability 视觉稿（OTel 部分）。

## 4. Actor
想理解一个 AI 处理工单背后技术执行路径的支持坐席/管理员。

## 5. 范围
从可观测性平台的查询界面按 ID（通过贯穿 domain 03/05/09 传播的 `traceparent` 关联）拉取一个 trace，并渲染为瀑布图（span 名称、起始偏移、耗时、嵌套）。

## 6. 非目标
构建任何新的追踪基础设施（domain 08 已完成）——这纯粹是对一个已有系统的控制台侧查询与渲染客户端。

## 7. 前置条件
一个工单存在关联的 trace ID（来自处理期间传播的任意 span 上下文）。

## 8. 输入
`traceId`。

## 9. 详细行为
从可观测性平台的查询 API 拉取该 trace 的 span 树，并渲染为按耗时比例、带嵌套的条形图，匹配视觉稿的瀑布图效果。

## 10. 交互状态迁移
不适用——一个只读可视化。

## 11. 业务不变量
一条本 domain 特有的新不变量：接入生产环境后，瀑布图必须渲染真实的 span 数据，绝不能是合成的/示意性占位符。

## 12. 幂等策略
不适用——是一个 `GET`。

## 13. 消费/依赖的契约
domain 08 可观测性平台的查询 API（其确切查询界面——例如 Tempo/Jaeger 兼容 API——接入前需对照 domain 08 自己的 API 契约文档确认）。

## 14. 安全
需要 domain 08 查询界面为 trace 访问定义的读取 scope（需对照 domain 08 自己的安全章节确认）。

## 15. 可观测性
元层面：本 spec 本身就是一个消费可观测性的功能；其自身的拉取也遵循 SPEC-SC-020 携带 `traceparent`。

## 16. 错误场景
trace 未找到（例如超出可观测性后端的保留窗口）——一个如实的"trace 已不再可用"状态，而不是空白/坏掉的视图。

## 17. 验收场景
一个带 5 个嵌套 span 的 trace，渲染出耗时相对关系和嵌套深度都正确的瀑布图。

## 18. 先写测试
针对匹配 domain 08 真实 span 树响应形状的 fixture 的组件测试。

## 19. 完成定义
瀑布图针对 fixture 正确渲染；domain 08 确切端点确认后追加针对真实查询 API 的兼容性检查。
