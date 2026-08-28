# Phase 01 — Dataset 与测试资产

> Domain：Evaluation Improvement
>
> Service：`evaluation-improvement-service`
>
> Phase：01
>
> Specs：`SPEC-EI-004` ～ `SPEC-EI-008`
>
> 前置条件：Phase 00 完成
>
> 文档状态：Implementation Plan

## 1. Phase 目标

实现 EvaluationDataset/TestCase、dataset version、golden dataset、case review/publish 和 artifact lineage。

## 2. 范围

包含：

- Dataset/TestCase 领域模型和状态机；
- Golden dataset 的 case schema、ground truth 和 validation；
- Dataset draft/review/publish/deprecate；
- case artifact 引用、hash、lineage 和脱敏字段；
- Dataset API 与权限校验。

不包含：

- Agent Runtime 执行 benchmark；
- Grader 评分实现；
- Candidate 自动生成；
- Canary 发布。

## 3. Specs

| 顺序 | SPEC | 名称 | 主要 LLD 映射 |
|---|---|---|---|
| 1 | `SPEC-EI-004` | Evaluation Dataset 聚合 | 01-domain-model, 03-state-machine |
| 2 | `SPEC-EI-005` | Evaluation Test Case Schema 与 Ground Truth | 01-domain-model, 07-data-model |
| 3 | `SPEC-EI-006` | Golden Dataset Review 与 Publish | 02-business-invariants, 11-security |
| 4 | `SPEC-EI-007` | Dataset Artifact、Hash 与 Lineage | 07-data-model, 09-concurrency-and-idempotency |
| 5 | `SPEC-EI-008` | Dataset API 与访问控制 | 05-api-contracts, 11-security |

## 4. 强制约束

- Published dataset 不可原地修改；
- Ground truth 变更必须产生新 version；
- Raw secret、token、credential 不得进入 dataset；
- Critical case 必须显式标记；
- Dataset publish 必须写 audit/outbox。

## 5. 退出条件

- MVP `identity-mfa-golden-dataset` 可创建、校验、发布；
- Dataset/TestCase migration、API、unit/integration/security tests 完成；
- 至少定义 30-50 个 Identity/MFA 场景的结构入口；
- dataset lineage 和 artifact hash 可追踪。

