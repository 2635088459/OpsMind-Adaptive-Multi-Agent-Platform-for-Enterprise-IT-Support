# SPEC-EP-014 — Ticket Status SSE（工单状态实时推送）

> Domain: `09-employee-portal` | Phase: 05 — 转人工与 Ticket 状态 | 状态：Spec Planning

## 1. Spec 身份
`SPEC-EP-014`，实现 `UC-EP-05` 的实时更新一半。

## 2. 目标
遵循冻结技术基线明确的 SSE-而非-WebSocket 选择，通过 Server-Sent Events 让 SPEC-EP-013 的面板保持实时更新。

## 3. 设计依据
`01-domain-model` §"TicketStatusView"；`05-api-contracts` §5（`GET /api/v1/tickets/{id}/events`，待建）；technology-baseline §4（SSE 决定）。

## 4. Actor
打开了工单状态面板的员工。

## 5. 范围
`useTicketStatusStream` hook：SSE 连接生命周期、事件到状态的映射；重连由单独的 spec 处理（SPEC-EP-020 做强化，本 spec 只覆盖基本的 happy-path 连接）。

## 6. 非目标
重连退避/强化细节（SPEC-EP-020）；SSE 端点自己的后端实现（待建，属于 `02-ticket-workflow`，尚未建成——这是一份真正全新的契约，不像 SPEC-EP-013 那个已经上线的 GET）。

## 7. 前置条件
SPEC-EP-013 的面板已挂载，且已成功拉取初始状态。

## 8. 输入
`ticketId`；SSE 流的 `status-changed`/`assignee-changed` 事件。

## 9. 详细行为
面板挂载时向 `GET /api/v1/tickets/{id}/events` 打开一个 `EventSource`；每次收到事件时合并进面板本地状态；卸载时关闭连接。

## 10. 交互状态迁移
仅是流本身简单的已连接/已断开生命周期，不是一个业务状态机。

## 11. 业务不变量
BI-EP-004——流式更新只能把展示状态往真实后端状态的方向推进，绝不能引入客户端自己发明的中间状态。

## 12. 幂等策略
SSE 事件被当作一个有序的完整状态快照流（不是需要去重的增量）——只要每个事件都带完整当前状态加单调递增的版本/时间戳（具体契约细节待后端端点设计后确认），重复/乱序事件就不成问题。

## 13. 消费/依赖的契约
`GET /api/v1/tickets/{id}/events`（待建——`02-ticket-workflow` 那边尚未设计；本 spec 自己的测试用 MSW/mock-SSE-server）。

## 14. 安全
需要与 SPEC-EP-013 相同的 `tickets:read` scope；SSE 鉴权走相同的 bearer-token 机制（浏览器 `EventSource` 无法设置自定义 header——真实契约必须考虑这一点，比如用短生命周期的 query-param token，这个细节标记为留给后端 spec 解决，本 spec 不假设已解决）。

## 15. 可观测性
连接建立/连接断开的客户端事件，可用于与 SPEC-EP-020 的重连指标关联。

## 16. 错误场景
流断开——恢复由 SPEC-EP-020 负责；本 spec 自己的范围只是不让面板崩溃，并回退到最后已知状态。

## 17. 验收场景
面板打开期间收到一个 status-changed 事件，展示状态无需刷新页面即可更新。

## 18. 先写测试
用 mock EventSource 写 hook 测试，断言每种事件类型都能正确合并状态。

## 19. 完成定义
happy-path 的流连接针对 mock SSE server 得到验证；真实后端端点存在后追加兼容性测试，鉴权 header 的细节在该端点自己的 spec 中解决。
