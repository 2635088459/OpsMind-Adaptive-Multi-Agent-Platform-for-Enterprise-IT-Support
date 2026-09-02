# SPEC-EP-006 — Turn State Machine（轮次状态机）

> Domain: `09-employee-portal` | Phase: 02 — 对话核心 | 状态：Implemented

## 1. Spec 身份
`SPEC-EP-006`。

## 2. 目标
把轮次状态机本身（`03-state-machine` §3.1）实现成一个独立、有充分测试覆盖的 Zustand store，不依附于任何单一组件——这样 SPEC-EP-005/007/008/012 都能驱动它，而不用各自重复迁移逻辑。

## 3. 设计依据
完整的 `03-state-machine` §3.1；`13-package-and-class-design` §4（状态管理选型）。

## 4. Actor
不适用——这是内部状态管理关注点，不是面向用户的独立行为。

## 5. 范围
`turnState` Zustand store 及其迁移函数，独立于任何 UI 做单元测试。

## 6. 非目标
不渲染任何东西——纯粹是被其他 spec 的组件消费的状态容器。

## 7. 前置条件
无。

## 8. 输入
迁移事件（`sendMessage`、`agentResponded`、`confirmClicked` 等），不是直接的 HTTP 请求。

## 9. 详细行为
只暴露 `03-state-machine` §3.1 声明的那些状态和迁移——`IDLE`、`SENDING`、`AWAITING_AGENT`、`AWAITING_CONFIRMATION`、`ACTION_EXECUTING`、`ESCALATED`、`AGENT_UNAVAILABLE`及其声明的那些边；非法迁移被拒绝（抛错/无操作），而不是被悄悄接受。

## 10. 交互状态迁移
本 spec 自己的主题——见 §9。

## 11. 业务不变量
在结构上强制 BI-EP-003：不存在从 `AWAITING_CONFIRMATION` 直接到有副作用状态、却不经过显式确认事件的迁移路径。

## 12. 幂等策略
这一层不适用——幂等键属于 HTTP 层（SPEC-EP-005 等），不属于状态机本身。

## 13. 消费/依赖的契约
无——纯客户端状态模块。

## 14. 安全
不适用。

## 15. 可观测性
本身不直接适用；其他 spec 自己的可观测性钩子会读取这个 store 的状态作为上下文。

## 16. 错误场景
尝试非法迁移（例如从 `IDLE` 直接确认）是本 spec 自己测试要抓住的编程错误，不是运行时面向用户的错误。

## 17. 验收场景
`03-state-machine` §3.1 里每一个合法迁移都被测试执行过；每一个非法迁移都被断言会被拒绝。

## 18. 先写测试
在任何组件消费这个 store 之前，先写一整套状态机单元测试。

## 19. 完成定义
`03-state-machine` §3.1 里声明的状态/迁移 100% 被单元测试覆盖。
