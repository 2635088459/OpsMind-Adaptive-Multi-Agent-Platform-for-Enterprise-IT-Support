# 07 Evaluation Improvement Implementation Roadmap

> Domain：Evaluation Improvement
>
> Service：`evaluation-improvement-service`
>
> 文档状态：Implementation Roadmap

## 1. 总目标

把 `07-evaluation-improvement` 从 LLD 落成可实现的 phase/spec：为 OpsMind 提供统一的 dataset、benchmark、grader、regression comparison、release gate、improvement candidate、Canary、rollback、线上抽样评估和质量审计能力。

07 不直接修改生产 Agent、Prompt、Policy、Tool、Ticket、Workflow 或 Memory；它只产出 evaluation facts、gate decision、candidate proposal 和 rollback recommendation。

## 2. Phase 总览

| Phase | 名称 | Specs | 目标 |
|---|---|---|---|
| 00 | 工程基础 | `SPEC-EI-001` ～ `SPEC-EI-003` | 建立 evaluation-improvement-service 的服务边界、schema baseline、outbox/processed-event/audit baseline。 |
| 01 | Dataset 与测试资产 | `SPEC-EI-004` ～ `SPEC-EI-008` | 实现 EvaluationDataset/TestCase、dataset version、golden dataset、case review/publish 和 artifact lineage。 |
| 02 | Benchmark Run 与执行器 | `SPEC-EI-009` ～ `SPEC-EI-013` | 实现 EvaluationRun、case runner、Agent Runtime evaluation endpoint 调用、LangSmith experiment linkage 和 run lifecycle。 |
| 03 | Grader 与评分体系 | `SPEC-EI-014` ～ `SPEC-EI-018` | 实现 deterministic grader、LLM Judge、grader registry、score persistence 和 judge calibration。 |
| 04 | Regression 与 Release Gate | `SPEC-EI-019` ～ `SPEC-EI-022` | 实现 baseline comparison、gate policy、critical case enforcement、regression report 和 CI gate。 |
| 05 | Improvement Candidate Lifecycle | `SPEC-EI-023` ～ `SPEC-EI-026` | 实现 failure clustering、candidate generation、benchmark binding、06 approval request 和 candidate finality。 |
| 06 | Canary 与受控发布 | `SPEC-EI-027` ～ `SPEC-EI-029` | 实现 canary plan、online sample evaluation、promotion criteria 和 rollback request。 |
| 07 | 跨域契约闭环 | `SPEC-EI-030` ～ `SPEC-EI-033` | 闭环 02 Ticket、03 Runtime、04 Memory、05 Tool Gateway、06 Policy Approval 和 08 Observability 的评估契约。 |
| 08 | 安全、观测、恢复 | `SPEC-EI-034` ～ `SPEC-EI-035` | 补齐 RBAC/ABAC、redaction、metrics/logs/traces、LangSmith outage、poison event 和 recovery。 |
| 09 | 最终验证与发布 | `SPEC-EI-036` | 完成 evaluation contract/e2e harness、最终覆盖审计、release readiness 和剩余风险登记。 |

## 3. 闭环原则

- 07 只产生 evaluation/improvement facts，不直接执行业务副作用。
- Dataset、grader、target version、policy version 和 baseline 必须可复现。
- Policy violation、forbidden tool call、unauthorized memory access 必须为 0。
- 安全门禁必须由 deterministic grader 判定，LLM Judge 不能单独通过安全规则。
- Candidate promotion 必须通过 release gate、06 approval、Canary 和 rollback guard。
- 所有状态迁移必须同事务写 audit/outbox。
- 所有消费事件必须 processed-event 去重。

