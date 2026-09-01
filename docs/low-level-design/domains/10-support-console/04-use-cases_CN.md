# Support Console — 用例

> **Document ID:** LLD-SC-004
> **Domain:** `10-support-console`
> **状态:** Draft

---

## UC-SC-01 查看分诊队列

**Actor:** 坐席
**主流程:** 打开控制台 → 默认展示自己所属团队队列 → 按严重程度排序 → 可搜索/筛选
**依赖:** `02-ticket-workflow` 真实的 support-queue 查询能力（已建成）

## UC-SC-02 查看工单详情与 AI 处理记录

**Actor:** 坐席
**主流程:**
1. 点击队列中一行 → 触发三路聚合请求（ticket timeline / tool-request 详情 / governance audit records）
2. 按时间顺序渲染成一条 `AiLogEntry[]` 时间线
3. 若存在待决策的审批请求，同时展示审批卡片
**验收标准:** 三路请求中任意一路失败时进入 `PARTIAL` 态（`03-state-machine` §3.2），不整体报错

## UC-SC-03 批准或拒绝一个审批请求

**Actor:** 坐席/管理员（需要审批相关权限）
**前置条件:** 存在 `status: "REQUESTED"` 的 `ApprovalRequestView`
**主流程:** 阅读风险等级+动作说明（BI-SC-006）→ 点击批准/拒绝 → 等待后端确认 → 卡片转为只读历史
**依赖:** `06-policy-approval-governance` 真实的 grant/deny 端点——已在 2026-09-01 集成验证里对同一条 ticket-workflow ↔ governance 链路真实跑通过

## UC-SC-04 手动分诊/指派/处理工单（坐席直接操作，AI 未处理或处理失败时）

**Actor:** 坐席
**主流程:** 与真实存在的 `02-ticket-workflow` 分诊/指派/状态流转端点直接对接（TriageTicketController/TicketAssignmentController/TransitionTicketStatusController 均已真实建成）
**验收标准:** 提交时携带当前已知版本号（If-Match），后端版本冲突时前端进入 `VERSION_CONFLICT`（BI-SC-005），不静默覆盖

## UC-SC-05 查看某工单处理过程的完整调用链路

**Actor:** 坐席/工程支持
**前置条件:** `AiLogEntry` 中至少一条携带非空 `traceId`
**主流程:** 点击"在 Tempo 中打开完整 Trace" → 用 `traceId` 拼出 Grafana/Tempo 的深链 URL → 新标签页跳转（不在控制台内自己渲染 trace 瀑布图的交互，viewport 内的瀑布图只是营销/预览级别的简化展示，真实排障始终外链到 Tempo 自己的界面）

## UC-SC-06 查看候选 Agent 版本对比（评测/灰度）

**Actor:** 管理员/工程负责人
**主流程:** 打开"可观测性·评测"页 → 查看 `07-evaluation-improvement` 真实的版本对比数据（通过率、回归数、灰度百分比）→ 点击"在 LangSmith 中查看完整实验"外链跳转
**非目标（本期）:** 不在控制台内提供发起新灰度/回滚的操作按钮——本期只读展示，写操作（调整灰度百分比）留给后续 phase，明确写入 non-goal

## 待新增/待聚合的能力清单

| 用例 | 现状 | 处理方式 |
|---|---|---|
| UC-SC-01 队列实时刷新 | 只有 REST 轮询 | MVP 先轮询（`03-state-machine` §3.1），SSE 推送作为 phase 2+ 优化，明确 non-goal |
| UC-SC-02 三路聚合 | 三个真实端点分别存在，无统一聚合端点 | 前端自己聚合调用（见 `05-api-contracts` §3 的理由） |
| UC-SC-06 版本对比数据 | 依赖 `07-evaluation-improvement` 真实 API，具体字段以该 domain 自己文档为准 | 本 LLD 不重新定义，届时对齐 |
