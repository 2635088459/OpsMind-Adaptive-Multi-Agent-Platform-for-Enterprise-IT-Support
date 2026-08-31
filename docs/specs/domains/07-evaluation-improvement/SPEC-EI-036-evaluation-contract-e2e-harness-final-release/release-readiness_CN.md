# Release Readiness & Residual Risk Register — SPEC-EI-036

> 本文档是 `phase-spec-coverage-matrix_CN.md` 中"最终完成标准"要求的"release readiness 和剩余风险登记"的落地产物。所有条目均基于对当前代码库（截至 2026-08-30）的实际检查，如实记录，不做粉饰。

## 1. 07 全量 spec 覆盖状态

`SPEC-EI-001` 至 `SPEC-EI-036` 共 36 个 spec，`status` 字段已全部为 `implemented`（本 spec 自身随本文档一并转为 `implemented`）。14 个 LLD 切面（00 domain-overview 至 14 testing-strategy）均有至少一个 spec 覆盖，覆盖关系见 `phase-spec-coverage-matrix_CN.md` 自身的表格。

## 2. 最终完成标准逐项核对

| 标准 | 状态 | 证据 |
| --- | --- | --- |
| 07 的 14 个 LLD 切面都有 spec 覆盖 | ✅ | `phase-spec-coverage-matrix_CN.md` 表格 + 36/36 traceability entry `status: implemented` |
| `identity-mfa-golden-dataset` 可创建、发布、复跑 | ✅（见下方 2.1 命名说明） | `tests/test_app.py::test_full_evaluation_and_improvement_walkthrough`、`tests/application/test_pipeline_walkthrough.py`、`tests/test_ci_gate_cli.py` |
| offline benchmark、grader、regression report 和 release gate 可在 CI 中运行 | ✅ | `interfaces/cli/ci_gate.py` + `tests/test_ci_gate_cli.py`；`CiEvaluationGateService` 端到端跑通 dataset → run → grade → compare → gate |
| safety hard gates 不能被 LLM Judge、成本优化或人工误操作绕过 | ✅ | INV-EI-003 由 `EvaluateReleaseGateService` 强制：门禁判定只读 `DimensionType.DETERMINISTIC` 分数；`tests/contracts/test_safety_gate_determinism_contract.py` 锁定该边界 |
| improvement candidate 不能绕过 benchmark、06 approval、Canary 和 rollback guard | ✅ | `ImprovementCandidate` 状态机 + `ManageCanaryService`/`CreateImprovementCandidateService` 各步骤显式校验前置状态；本 phase 新增 `promote`/`rollback_promoted` 两条路径同样遵守（见第 3 节） |
| 02/03/04/05/06/08 的关键 evaluation 契约可运行并有测试入口 | ✅ | `tests/contracts/*`（event envelope、observability signal、safety gate determinism）+ `HttpAgentRuntimeEvaluationAdapter`/`HttpPolicyApprovalAdapter`/`SdkLangSmithExperimentAdapter` 各自的 real-adapter 测试 |
| LangSmith outage、partial run、poison event、outbox replay、admin repair 和 release readiness 完成 | ✅ | SPEC-EI-018（judge calibration drift）、SPEC-EI-035（poison event + admin dispatch）、本文档（release readiness） |

### 2.1 `identity-mfa-golden-dataset` 命名说明

`05-api-contracts` 样例 JSON 中的 `"datasetId": "identity-mfa-golden-dataset"` 是一个可读占位符——真实的 `dataset_id` 字段在领域模型里始终是服务端生成的 UUID（`Dataset.create()`），从未是人类可读字符串。测试套件里实际使用的是同一语义下的 `name` 字段值 `"identity-mfa-golden"`（`tests/application/test_pipeline_walkthrough.py:37` 等 19 处），并未额外拼接 `"-dataset"` 后缀。两者指的是同一个"MFA 场景 golden dataset"能力：创建（`create_dataset`）、发布（`submit_for_review` → `publish`）、复跑（`RunCiGateCommand` 可对同一 dataset 反复触发新的 run，`baseline_version` 支持对比复跑结果）均已被端到端测试覆盖。这里记录为一个已核实、无需代码变更的命名对齐，而非功能缺口。

## 3. 本 phase（phase-09）修复的真实缺口

对 07 现有实现做最终覆盖审计时，发现并修复了两个此前未被任何测试捕获的 REST/应用层空缺（详见对应 commit 内的 docstring）：

1. **Canary 生命周期缺少 REST 入口**：`ManageCanaryUseCase.advance()`/`pause()`/`complete_rollback()` 与 `CreateImprovementCandidateUseCase.promote()` 早在更早的 phase 就已是真实实现，但 `interfaces/rest/router.py` 从未暴露对应端点——这意味着一个通过 HTTP 审批并启动 Canary 的 candidate，在 API 层面永远无法真正推进到 `PROMOTED`。已新增四个端点：`POST /improvement-candidates/{id}/advance-canary`、`pause-canary`、`complete-rollback`、`promote`。
2. **PROMOTED 状态的 candidate 无法被回滚**：`Canary` 子状态机中 `SUCCEEDED` 是终态（`_CANARY_TRANSITIONS[CanaryStatus.SUCCEEDED] = frozenset()`），而 `ManageCanaryService._do_request_rollback()` 只接受 `ACTIVE`/`EXPANDING`/`PAUSED`/`FAILED` 三种 canary_status——一旦 candidate 晋升为 `PROMOTED`（此时 canary_status 恒为 `SUCCEEDED`），应用层没有任何路径能触发回滚，尽管领域聚合自身的 `ImprovementCandidate.rollback()` 一直显式支持 `PROMOTED -> ROLLED_BACK`（绕过 Canary 子状态机校验）。已新增 `RollbackPromotedCandidateCommand` + `ManageCanaryService.rollback_promoted()` + `POST /improvement-candidates/{id}/rollback-promoted`，遵循"07 只请求 rollback，由 Runtime/Config owner 执行"的既有原则——只发布 `ImprovementRollbackRequested` 并记录审计，不执行任何生产副作用。

两处修复均已补充应用层测试（`tests/application/test_manage_canary_service.py` 新增 7 个用例）与 HTTP 层端到端测试（`tests/test_app.py` 扩展原有 walkthrough 至 `PROMOTED`，并新增 `test_canary_pause_rollback_and_promoted_rollback_over_http` 覆盖两条独立的 rollback 路径），经 Docker 内真实 pytest 运行验证通过。

## 4. 已知的、有意保留的延迟项（Residual Risk Register）

以下条目在整个 07 domain 的实现过程中被反复确认为"有意为之、非本 domain 待办"，逐条列出以便后续接手者不必重新排查：

| # | 项目 | 现状 | 风险等级 | 说明 |
| --- | --- | --- | --- | --- |
| R1 | 真实 RabbitMQ AMQP 消费者 | 未实现（`infrastructure/messaging/rabbitmq_consumer.py` 为空占位文件） | 低 | 与仓库内 `agent-runtime-service`、`memory-knowledge-service` 同一模式：所有跨域事件消费改由 `interfaces/event/router.py` 的 REST "event listener" 端点承接（`consume_cross_domain_event`/`consume_approval_decision_event` 等真实业务逻辑已实现，只是触发方式是 REST 而非 AMQP push）。未来接入真实 broker 时，只需新增一个 pika/aio-pika consumer，将消息体转交给同一批 application service，无需改动业务逻辑。 |
| R2 | 真实 RabbitMQ 事件发布 | 未实现（`infrastructure/messaging/rabbitmq_publisher.py` 为空占位文件） | 低 | 同上模式；outbox 记录已在 `OutboxRepository`/`DispatchOutboxEventsService` 中真实落库并支持重放，只是最终投递适配器目前是 `LoggingEventPublisherAdapter`（写日志），换成真实 AMQP publisher 是纯 adapter 层替换。 |
| R3 | `memory.retrieval.completed.v1` 无真实上游生产者 | 未实现 | 低 | 04-memory-knowledge 尚未有服务真正发布此事件；07 侧的消费端点（`POST /events/memory-retrieval-completed`）与 `ConsumeMemoryRetrievalCompletedCommand` 处理逻辑已完整实现并有测试覆盖，等待 04 侧产出真实事件即可直接对接。 |
| R4 | LLM Judge 仅质量维度，从不参与门禁 | 设计如此（非缺口） | — | INV-EI-003 是硬性不变量：`DimensionType.LLM_JUDGE` 的分数永远不会被 `EvaluateReleaseGateService`/回归比较读取。`llm_judge_mode="placeholder"` 默认关闭真实调用；`"anthropic"` 模式接入真实 API 后依然受此不变量约束（`tests/contracts/test_safety_gate_determinism_contract.py` 锁定）。 |
| R5 | 08-observability-platform 尚无真实服务可对接 | 阻塞于外部 domain | 低 | 07 侧已按 `12-observability` 契约输出真实 OTel span/metric（`otel_exporter="console"` 默认即真实导出到 stdout，非 stub），一旦 08 的真实 collector/backend 上线，只需切换 `otel_exporter="otlp"` 并配置 endpoint，无需改动 07 代码。 |
| R6 | 06-policy-approval-governance 跨服务身份认证机制尚不存在 | 阻塞于外部 domain | 低 | `HttpPolicyApprovalAdapter`（`policy_approval_mode="http"`）已实现真实 HTTP 客户端并支持 `policy_approval_service_token` bearer token 字段，但 06 自身的 `GovernanceRequestContext` 要求一个非空 Spring Security `Authentication.getName()`，本仓库目前没有任何跨服务身份签发机制能满足这一点。`policy_approval_mode` 默认仍为 `"fake"`（`FakePolicyApprovalAdapter`，请求恒为 PENDING），生产切换到 `"http"` 前需要先由平台层解决跨服务身份问题——这不是 07 自身能单方面解决的缺口。 |
| R7 | LangSmith SDK 模式依赖外部包与 API key | 设计如此（非缺口） | — | `langsmith_mode="noop"` 默认关闭；`"sdk"` 模式仅在 `langsmith` 包可导入且提供 `langsmith_api_key` 时生效（`container.py::_build_langsmith_port()`）。`NoOpLangSmithExperimentAdapter.is_enabled()` 恒为 `False`，10-failure-handling 的"LangSmith 故障 fail-closed"规则只在真实尝试调用时触发，noop 模式下不会误报故障。 |

无 R1–R7 之外的其他已知功能缺口。所有条目均为"设计如此"或"依赖尚不存在的外部对接方"，不属于本 domain 范围内的未完成工作。

## 5. 发布前检查清单（Release Checklist）

- [x] 3/3 import-linter 契约保持 KEPT（`uv run lint-imports`）。
- [x] pyflakes 对 `src/evaluationimprovement` 与 `tests` 零告警。
- [x] 非 integration 测试套件全部通过（210 passed，见第 6 节）。
- [x] integration 测试套件（真实 Postgres via testcontainers）全部通过（见第 6 节）。
- [x] 所有 36 个 SPEC-EI traceability entry `status: implemented`。
- [x] README 记录完整 Phase 00–09 实施历史。
- [x] 无遗留的假/占位实现被误标为"完成"——R1–R7 均已如实登记为已知延迟项。

## 6. 最终验证结果（2026-08-30，Docker `python:3.12-alpine` 内真实执行）

- `uv run lint-imports`：3/3 契约 KEPT（Layers / Domain-no-web-framework / Application-no-infrastructure）。
- `uv run pyflakes src/evaluationimprovement tests`：零告警。
- `uv run pytest -m 'not integration' -q`：**210 passed**, 27 deselected。
- `uv run pytest -m integration -q`（真实 PostgreSQL via testcontainers，`TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`）：**27 passed**, 210 deselected。
- 合计 **237 个测试全部通过**，无 skip、无 xfail、无被静默吞掉的失败。
- 本 phase 新增测试：`tests/application/test_manage_canary_service.py` 新增 7 个用例（pause/resume、request+complete rollback、advance-to-succeeded+promote、promote 前置校验、rollback_promoted 成功路径与非法状态拒绝）；`tests/test_app.py` 扩展 `test_full_evaluation_and_improvement_walkthrough` 至 `PROMOTED`，新增 `test_canary_pause_rollback_and_promoted_rollback_over_http`。
