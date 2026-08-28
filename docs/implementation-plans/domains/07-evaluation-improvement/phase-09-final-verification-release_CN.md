# Phase 09 — 最终验证与发布

> Domain：Evaluation Improvement
>
> Service：`evaluation-improvement-service`
>
> Phase：09
>
> Specs：`SPEC-EI-036`
>
> 前置条件：Phase 08 完成
>
> 文档状态：Implementation Plan

## 1. Phase 目标

完成 evaluation contract/e2e harness、最终覆盖审计、release readiness 和剩余风险登记。

## 2. 范围

包含：

- 07 全量 spec 覆盖审计；
- golden dataset e2e benchmark；
- candidate approval + canary + rollback e2e；
- cross-domain contract test harness；
- final release checklist；
- residual risk register。

不包含：

- 新增超出 MVP 的 evaluation dimension；
- 生产自动调参；
- 多模型大规模实验平台；
- 外部 BI 平台建设。

## 3. Specs

| 顺序 | SPEC | 名称 | 主要 LLD 映射 |
|---|---|---|---|
| 1 | `SPEC-EI-036` | Evaluation Contract/E2E Harness 与最终发布准备 | 14-testing-strategy |

## 4. 强制约束

- 07 的 14 个 LLD 切面必须全部有 spec 覆盖；
- MVP release gate 必须在 CI 中可运行；
- forbidden tool、policy violation、unauthorized memory access 必须为 0；
- candidate 不能绕过 06 approval；
- final audit 必须列出剩余风险和 deferred scope。

## 5. 退出条件

- `identity-mfa-golden-dataset` benchmark 可复跑；
- release gate、regression report、candidate approval、Canary、rollback e2e 通过；
- phase/spec coverage matrix 无关键缺口；
- 所有必需测试证据、traceability 和 release notes 完成。

