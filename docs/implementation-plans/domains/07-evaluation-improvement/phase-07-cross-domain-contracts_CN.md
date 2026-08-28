# Phase 07 — 跨域契约闭环

> Domain：Evaluation Improvement
>
> Service：`evaluation-improvement-service`
>
> Phase：07
>
> Specs：`SPEC-EI-030` ～ `SPEC-EI-033`
>
> 前置条件：Phase 06 完成
>
> 文档状态：Implementation Plan

## 1. Phase 目标

闭环 02 Ticket、03 Runtime、04 Memory、05 Tool Gateway、06 Policy Approval 和 08 Observability 的评估契约。

## 2. 范围

包含：

- Ticket resolution/reopen/SLA/feedback contract；
- Runtime workflow trace、agent task、evaluation execution contract；
- Memory retrieval trace、ACL、provenance contract；
- Tool result envelope、tool argument、forbidden call contract；
- Policy approval/release approval contract；
- Observability metrics/logs/traces semantic field contract。

不包含：

- 修改各域核心状态机；
- 将 07 变成统一 audit platform；
- 跨域表直接写入。

## 3. Specs

| 顺序 | SPEC | 名称 | 主要 LLD 映射 |
|---|---|---|---|
| 1 | `SPEC-EI-030` | Ticket 与 Runtime Evaluation Contract | 05-api-contracts, 06-event-contracts, 14-testing-strategy |
| 2 | `SPEC-EI-031` | Memory 与 Tool Evidence Contract | 06-event-contracts, 11-security |
| 3 | `SPEC-EI-032` | Policy Approval 与 Release Approval Contract | 05-api-contracts, 06-event-contracts |
| 4 | `SPEC-EI-033` | Observability Evaluation Signal Contract | 12-observability, 14-testing-strategy |

## 4. 强制约束

- 07 只能消费其他域事实事件或调用显式 evaluation API；
- 不得从其他域数据库直接读取业务表；
- 所有 cross-domain payload 必须有 version、correlation id 和 PII classification；
- contract drift 必须让 contract test failed；
- 缺失关键 evidence 时 gate 不能 passed。

## 5. 退出条件

- 02/03/04/05/06/08 的关键契约有 schema 和测试；
- replay/duplicate event 场景通过；
- missing evidence、invalid payload、PII violation 均有测试；
- end-to-end golden path evaluation harness 可运行。

