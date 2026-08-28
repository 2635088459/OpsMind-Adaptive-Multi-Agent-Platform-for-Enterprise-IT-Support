# Phase 03 — Grader 与评分体系

> Domain：Evaluation Improvement
>
> Service：`evaluation-improvement-service`
>
> Phase：03
>
> Specs：`SPEC-EI-014` ～ `SPEC-EI-018`
>
> 前置条件：Phase 02 完成
>
> 文档状态：Implementation Plan

## 1. Phase 目标

实现 deterministic grader、LLM Judge、grader registry、score persistence 和 judge calibration。

## 2. 范围

包含：

- deterministic grader registry；
- classification/root cause/tool allowlist/forbidden tool/tool args/approval/final state/verification grader；
- LLM Judge 用于 explanation quality、evidence grounding、handoff completeness、user instruction clarity；
- score persistence、failure code 和 evidence ref；
- judge calibration dataset 和 drift detection。

不包含：

- release gate 策略计算；
- candidate generation；
- canary rollout。

## 3. Specs

| 顺序 | SPEC | 名称 | 主要 LLD 映射 |
|---|---|---|---|
| 1 | `SPEC-EI-014` | Deterministic Grader Registry | 13-package-and-class-design, 02-business-invariants |
| 2 | `SPEC-EI-015` | Safety 与 Policy Compliance Graders | 02-business-invariants, 11-security |
| 3 | `SPEC-EI-016` | Quality LLM Judge Graders | 01-domain-model, 10-failure-handling |
| 4 | `SPEC-EI-017` | Evaluation Score Persistence | 07-data-model, 08-transaction-and-outbox |
| 5 | `SPEC-EI-018` | Judge Calibration 与 Drift Guard | 12-observability, 14-testing-strategy |

## 4. 强制约束

- Policy compliance、forbidden tool、required approval 必须 deterministic；
- LLM Judge failure 不能让安全门禁 passed；
- 每个 score 必须绑定 grader type/version 和 evidence ref；
- Grader bundle 发布后不可静默替换；
- Critical dimension 缺失时 gate 必须 failed。

## 5. 退出条件

- MVP deterministic graders 全部可运行；
- LLM Judge 只覆盖质量类 dimension；
- score 表和 batch write 测试完成；
- calibration drift 超阈值会禁用 judge bundle。

