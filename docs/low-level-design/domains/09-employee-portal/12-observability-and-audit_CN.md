# Employee Portal — 可观测性与审计

> **Document ID:** LLD-EP-012
> **Domain:** `09-employee-portal`
> **状态:** Draft

---

## 1. 前端在两层可观测性体系里的角色

沿用 shared technology-baseline §10/§11 已经定死的两层划分：

```text
OpenTelemetry = 工程可观测性（跨服务调用链路）
LangSmith     = agent 语义可观测性（prompt/completion/工具轨迹）
```

前端**只参与第一层**，且只是"起点"，不做后端才该做的事：

- 每次 API 调用生成/透传 `trace_id`（W3C Trace Context header，`traceparent`），让后端各个服务（03 号 domain 编排链路里可能经过的 04/05/06 号 domain）能把同一次员工请求的全链路串起来——这正是 `Agent Observability` 视觉稿里那个真实跑通过的 Trace 瀑布图的起点（见 `project-level-integration-verification` memory 里 INC-2481 那条真实链路）。
- **不**自己创建 span 上报到 Tempo——前端只是 header 的携带者，真正的 span 由各后端服务产生。
- **不**接触 LangSmith——按 §5（`11-security-and-authorization`）已经说明的边界，这条线完全在服务端。

## 2. 客户端自身的错误上报（前端专属，非跨服务追踪）

- 未捕获的前端异常（渲染错误、Promise rejection）上报到一个轻量错误收集端点（例如 Sentry 兼容协议）——这是纯前端工程实践，不属于平台级的 OTel/LangSmith 两层体系，MVP 阶段可以先只做 `console.error` + 本地埋点，真正接入外部服务是 phase 2+ 的事，明确写进 non-goal。

## 3. 审计：前端不产生审计记录，只是审计事件的触发源

真正的审计记录（`AuditRecordEntry` 这类）由后端各 domain 在处理真实业务操作时写入（`02-ticket-workflow`/`06-policy-approval-governance` 已经真实实现）。前端的责任只是：**每一个会触发后端审计的操作，都带上真实的 actor 身份**（来自 JWT `sub`）和 `correlationId`——不缺失、不伪造，这样后端审计记录里的 `actorId`/`traceId` 才对得上员工在门户里实际做过的操作。

## 4. 需要监控的前端专属指标（供未来仪表盘参考，MVP 不强制）

```text
首次消息发送到收到 agent 响应的耗时（前端感知的端到端延迟）
附件上传成功率
SSE 重连频率
会话过期打断发送的频率（衡量 BI-EP-006 的实际触发情况）
```

这些不是本 phase 的交付物，列在这里是为了让 `13-package-and-class-design`/`14-testing-strategy` 在设计埋点钩子位置时心里有数，避免以后要重构。
