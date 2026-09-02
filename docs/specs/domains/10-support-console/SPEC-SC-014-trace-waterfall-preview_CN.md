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
不直接调用：domain 08 自己的安全文档（`ObservabilityAccessControl.md`，SPEC-OP-030）确认 Tempo/Loki 的查询 API 被有意保留为无认证状态（当时没有共享 Keycloak 可用于对其加认证）。没有让浏览器直接面对该界面——这会是本平台第一次真正把浏览器指向一个无认证后端，是一次真实的暴露面扩大——而是给 `user-access-authentication-service`（已有的 BFF）新增了一个带认证的代理端点 `GET /api/v1/observability/traces/{traceId}`（`TraceWaterfallController`/`TraceWaterfallService`/`TempoQueryClient`），且在动手前先与用户确认过（这是一次真正有实质影响、跨 domain 边界的决策，而非单方面决定）。本 console 实际调用的正是这个代理端点。

Tempo 真实的查询 API（`GET {tempoBaseUrl}/api/traces/{traceId}`，`X-Scope-OrgID` 对应 SPEC-OP-031）已对照一个真实运行中的 `observability-stack.yml` 现场确认过，而非照搬文档假设——发现 2 个如果不处理会悄悄破坏实现的非显而易见细节：(a) 响应是原始 OTLP-JSON（`batches[].resource/scopeSpans[].spans[]`），并非最初假设的 Jaeger 兼容形状；(b) `traceId`/`spanId`/`parentSpanId` 是 base64（proto3 字节字段的 JSON 约定，而非十六进制），`startTimeUnixNano`/`endTimeUnixNano` 是 JSON 字符串，其值超出 `Number.MAX_SAFE_INTEGER`——两者都在服务端（Java `long`/`Base64`/`HexFormat`）解码，从不原样交给浏览器。同样现场确认了 ADR-0011 的真实后果：单条工单处理 trace 的 span 可能被真实拆分到多个 Tempo 租户下；代理会查询一份固定的真实租户列表并合并返回结果，对完全无法查通的租户（不同于该租户本就没碰到这条 trace）如实报告，沿用 SPEC-SC-007/019 建立的"故障 vs 缺失"区分原则。

## 14. 安全
仅限 `support-console` 这一 OAuth2 client registration（domain 09 员工门户的会话会被拒绝，403 `TRACE_ACCESS_DENIED`）——与 `BrowserSessionTokenController` 相同的会话 cookie 认证，不涉及 bearer token。

## 15. 可观测性
元层面：本 spec 本身就是一个消费可观测性的功能；其自身的拉取也遵循 SPEC-SC-020 携带 `traceparent`。

## 16. 错误场景
在所有已查询租户下均未找到 trace（例如超出 Tempo 的保留窗口)——一个真实、干净的 404 `TRACE_NOT_FOUND`，渲染为如实的"trace 已不再可用"状态。与"所有已查询租户均完全无法响应"（Tempo/网络不可达）——一个真实、可重试的 503 `TRACE_QUERY_UNAVAILABLE`——明确区分，绝不合并成同一个通用错误。

## 17. 验收场景
一个带嵌套 span 的 trace，渲染出耗时相对关系和嵌套深度都正确的瀑布图——已对照 fixture 验证，且已对照真实运行中的技术栈现场验证（一条真实推送的 trace，base64 解码与偏移量计算均正确）。

## 18. 先写测试
针对匹配真实 `TraceWaterfallView` 响应形状的 fixture 的组件测试（成功、404、503、部分故障）；后端针对 Tempo 响应解析（现场确认形状的真实 OTLP-JSON fixture）、租户合并/故障逻辑、registration 门禁的单元测试。

## 19. 完成定义
瀑布图针对每一种真实结果（成功/404/503/部分故障）正确渲染 fixture；支撑它的带认证代理是真实、已测试、且已对照一个真实运行中的 `observability-stack.yml`、通过一个真实的 support-console 浏览器会话端到端现场验证（而不仅仅是与 fixture 兼容）。
