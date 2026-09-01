# SPEC-EP-013 — Ticket Status Panel（工单状态面板）

> Domain: `09-employee-portal` | Phase: 05 — 转人工与 Ticket 状态 | 状态：Spec Planning

## 1. Spec 身份
`SPEC-EP-013`，实现 `UC-EP-05` 的初始渲染。

## 2. 目标
渲染 `TicketStatusView` 侧边面板，展示该对话所转出工单真实、已存在的状态（状态值、负责人（如有）、最后更新时间）。

## 3. 设计依据
`01-domain-model` §"TicketStatusView"；`04-use-cases` UC-EP-05；`05-api-contracts` §5（真实、已建成的 `GET /api/v1/tickets/{id}` 端点）。

## 4. Actor
正在查看对话、想确认工单进展的员工。

## 5. 范围
面板的初次拉取与渲染；实时更新是 SPEC-EP-014 的关切。

## 6. 非目标
实时推送（SPEC-EP-014）；任何工单修改操作（对员工只读——修改属于 support console 的领域）。

## 7. 前置条件
该对话已关联一个 `ticketId`（自消息一起就为真，遵循 domain 03 phase-10 设计确立的行为）。

## 8. 输入
该对话的 `ticketId`。

## 9. 详细行为
打开面板时拉取 `GET /api/v1/tickets/{id}`（真实、已实现）并渲染状态/负责人/最后更新时间；等待期间展示 loading skeleton。

## 10. 交互状态迁移
不适用——这是一个只读展示，本身没有状态机。

## 11. 业务不变量
BI-EP-004——面板必须展示后端真实的工单状态，绝不能是客户端猜测或乐观值。

## 12. 幂等策略
不适用——是一个 `GET`，天然幂等。

## 13. 消费/依赖的契约
`GET /api/v1/tickets/{id}`——真实、已由 `02-ticket-workflow` 实现（2026-09-01 集成验证中确认线上可用）。

## 14. 安全
需要员工自己的 `tickets:read` scope，现有鉴权已授予（无需新 scope——这是 domain 09 目前为止第一个消费已线上可用契约、零新增后端工作量的 spec）。

## 15. 可观测性
拉取时传播 `traceparent`，与 `12-observability-and-audit` §1 一致。

## 16. 错误场景
拉取失败 → 面板展示重试操作，绝不展示陈旧或编造的状态。

## 17. 验收场景
在一个状态为 `IN_PROGRESS` 且有负责人的工单上打开面板，两个字段都从真实后端正确渲染。

## 18. 先写测试
针对真实 ticket-read 契约的响应形状写组件测试（这是 domain 09 目前为止唯一一个能直接对真实形状测试、而不只是 MSW mock 的 spec，因为后端已经存在）。

## 19. 完成定义
面板正确渲染真实工单状态；同时被契约测试和组件测试覆盖。
