# SPEC-EP-007 — Proposed Action Card（方案提议卡片）

> Domain: `09-employee-portal` | Phase: 03 — 自助方案确认 | 状态：Implemented

## 1. Spec 身份
`SPEC-EP-007`。

## 2. 目标
把 `ProposedAction`渲染成 `ProposedActionCard` 组件：完整、不截断的说明，加确认/拒绝按钮。

## 3. 设计依据
`01-domain-model` §"ProposedAction"；`02-business-invariants` BI-EP-007；`13-package-and-class-design` §5。

## 4. Actor
刚收到 `ProposedAction` 响应的员工。

## 5. 范围
仅 `ProposedActionCard` 组件本身——它自己的渲染，以及把点击事件转发给 SPEC-EP-008/009 的 hooks。

## 6. 非目标
真正的确认/拒绝网络调用（SPEC-EP-008/009）。

## 7. 前置条件
轮次状态为 `AWAITING_CONFIRMATION`。

## 8. 输入
一个 `ProposedAction` 对象。

## 9. 详细行为
完整渲染 `summary`（BI-EP-007：不做 CSS 截断、不用省略号，不管视口宽度多少）加确认/拒绝按钮。

## 10. 交互状态迁移
仅展示层；迁移本身属于 SPEC-EP-006 的 store，由 SPEC-EP-008/009 触发。

## 11. 业务不变量
BI-EP-007（本 spec 存在的直接原因）——由一个组件测试直接强制验证，断言 `summary` 上没有应用 `text-overflow: ellipsis`/`overflow: hidden`。

## 12. 幂等策略
不适用——纯渲染。

## 13. 消费/依赖的契约
无直接依赖——消费 SPEC-EP-005 已经返回的 `ProposedAction` 形状。

## 14. 安全
只把 `summary` 渲染为纯文本/受限 Markdown，从不渲染原始 HTML（`11-security-and-authorization` §4）。

## 15. 可观测性
不适用。

## 16. 错误场景
不适用——格式不对的 `ProposedAction`（缺 `summary`）是 SPEC-EP-005 自己的契约测试要抓的契约违反，不在本 spec 处理。

## 17. 验收场景
真实长度的 `summary`（对应视觉稿自己的文案）在常见视口宽度下（含移动端）完整渲染。

## 18. 先写测试
断言没有截断样式、两个按钮都正确转发 `actionId` 的组件测试。

## 19. 完成定义
BI-EP-007 由自动化测试验证，不只是人工肉眼检查。
