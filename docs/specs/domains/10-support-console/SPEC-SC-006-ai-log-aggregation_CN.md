# SPEC-SC-006 — AI Log Aggregation（AI 处理日志聚合）

> Domain: `10-support-console` | Phase: 03 — AI 透明度面板 | 状态：Spec Planning

## 1. Spec 身份
`SPEC-SC-006`，实现 `UC-SC-02`，本 domain 架构上最独特的一个 spec。

## 2. 目标
遵循 `05-api-contracts` §3 记录的前端侧聚合决定（不新建 BFF 服务），通过并发拉取并合并 3 个真实、分属不同 domain 的端点，构建 `AiLogEntry` 视图——一条统一的时间线，展示 agent 在一个工单上做了什么。

## 3. 设计依据
`01-domain-model` §"AiLogEntry"；`04-use-cases` UC-SC-02；`05-api-contracts` §3（聚合架构决定本身）。

## 4. Actor
在一个工单转人工之前，正在查看 AI 是如何处理它的支持坐席。

## 5. 范围
并发拉取 `GET /api/v1/tickets/{id}/timeline`、`GET /api/v1/governance-audit-records`、`GET /api/v1/tool-requests/{id}`（均真实、已实现）；客户端合并为一条按时间排序的 `AiLogEntry` 列表。

## 6. 非目标
任何新的后端聚合端点（明确被拒绝，转而采用前端侧聚合，遵循 LLD 自己的架构决定）——本 spec 不能悄悄滑向想要一个 BFF；如果 3-fetch 方式确实证明不够用，那应该重新打开 LLD 决定，而不是在这里绕过去打补丁。

## 7. 前置条件
一个工单存在关联的 agent 处理历史（即通过 domain 09 的对话流程创建，或以其他方式被 `agent-runtime-service` 处理过）。

## 8. 输入
`ticketId`。

## 9. 详细行为
并发触发全部 3 个拉取（`Promise.allSettled`，不是 `Promise.all`——一次部分失败不能使整个面板空白，参见 SPEC-SC-007）；把成功的结果按时间戳合并为一条时间线；每条条目标记其来源端点以便追溯。

## 10. 交互状态迁移
不适用——一个只读聚合视图。

## 11. 业务不变量
BI-SC-003（遵循 domain 10 LLD，AI 日志保真度）——合并后的时间线绝不能在某个子拉取失败时悄悄丢弃条目而不给出提示（参见 SPEC-SC-007）。

## 12. 幂等策略
不适用——3 个都是 `GET`。

## 13. 消费/依赖的契约
全部 3 个真实端点：工单时间线（domain 02）、治理审计记录（domain 06）、工具请求（domain 05）——跨 domain 聚合，是 domain 10 中唯一一个同时触及 3 个后端 domain 的 spec。

## 14. 安全
需要 3 个端点 scope 的并集——一个真正的跨 domain 授权面，标记给安全阶段（SPEC-SC-018/019）专门审计。

## 15. 可观测性
3 个并发拉取都带 `traceparent`，理想情况下为这一整个聚合操作共享一个公共父 span。

## 16. 错误场景
3 个拉取中任意一个失败——由 SPEC-SC-007 的部分降级设计处理，不在此处悄悄忽略。

## 17. 验收场景
一个来自全部 3 个来源都有条目的工单，渲染出一条统一、正确按时间排序的时间线。

## 18. 先写测试
针对全部 3 个端点的 fixture 写组件测试，断言正确的时间排序合并和来源标记。

## 19. 完成定义
合并逻辑针对匹配全部 3 个真实契约形状的 fixture 被证明正确；针对真实端点运行一套兼容性测试。
