# 08 可观测性与平台基础设施

> 状态：设计启动  
> 核心栈：OpenTelemetry Collector、Prometheus、Loki、Tempo、Grafana、Alertmanager

## 1. 领域目标

08 为 OpsMind 提供统一 logs、metrics、traces、correlation、dashboard、alert、SLO、error budget、容量与成本观测能力，使一次用户请求可以从 Portal/API Gateway 贯穿 01–07、RabbitMQ、PostgreSQL 和外部 Connector。

## 2. 所有权

08 拥有：遥测接入规范、Collector pipeline、指标/日志/Trace 存储配置、recording/alert rules、dashboard、SLO/error budget、告警路由、telemetry retention/redaction、平台健康与恢复证据。

08 不拥有：Ticket/Workflow/Tool/Policy/Memory/Identity/Evaluation 业务事实；不根据告警直接修改业务状态；不把 dashboard 计算值反写为领域事实。07 保留 evaluation result 的事实所有权，08 只展示与告警。

## 3. 部署边界

```text
Java/Python services + infrastructure exporters
                  │ OTLP gRPC/HTTP
                  ▼
       OpenTelemetry Collector Gateway
          ├── metrics → Prometheus
          ├── logs    → Loki
          └── traces  → Tempo
                         │
                    Grafana/Alertmanager
```

不为遥测 ingestion 创建通用业务微服务。只有当 GitOps 无法安全表达 SLO、alert、silence、retention 管理时，才增加薄控制面 API，并要求 01 身份授权、06 高风险审批和不可变审计。

## 4. 强制原则

- 所有信号使用 `service.name`, `service.version`, `deployment.environment`, `trace_id`, `correlation_id`。
- 禁止 token、password、cookie、Authorization Header、MFA secret、完整 prompt、原始用户文本和未脱敏 PII 进入遥测。
- metrics label 必须低基数；user/ticket/workflow ID 不得作为 Prometheus label。
- Trace context 必须跨 HTTP 与 RabbitMQ 传播，消费者创建 linked/child span。
- Collector/后端故障不能阻塞核心业务；SDK 使用有界队列、batch 和 drop metrics。
- 告警必须可执行，包含 severity、owner、runbook、dashboard 和 correlation 查询入口。
- Production dashboard、alert、SLO、retention 变更通过版本控制、review 和审计。

## 5. LLD 工作包

后续按统一 14 切面补齐：domain model、business invariants、state machines、use cases、API、signal/event contracts、data model、transaction/config publication、concurrency/idempotency、failure handling、security、observability-of-observability、package/config design、testing strategy。
