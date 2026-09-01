# Employee Portal — 包结构与组件设计

> **Document ID:** LLD-EP-013
> **Domain:** `09-employee-portal`
> **状态:** Draft
> **技术基线:** React 19 + Vite 8.x + TypeScript（shared baseline §4，冻结）

---

## 1. 目录结构

```text
apps/employee-portal/
├── src/
│   ├── app/                     # 应用入口、路由、Provider 组合
│   │   ├── router.tsx
│   │   └── providers.tsx        # QueryClientProvider / ThemeProvider 等
│   ├── features/
│   │   ├── conversation/        # 对话核心：Message、Composer、ProposedActionCard
│   │   │   ├── components/
│   │   │   ├── hooks/           # useSendMessage、useConfirmAction（TanStack Query）
│   │   │   └── store.ts         # Zustand：turnState 等瞬时交互态
│   │   ├── ticket-status/       # 工单进展面板
│   │   │   ├── components/
│   │   │   └── hooks/           # useTicketEvents（SSE 订阅）
│   │   └── auth/                # 登录跳转、会话状态、草稿恢复（BI-EP-006）
│   ├── components/               # 跨 feature 共享的展示层组件（基于 shadcn/ui）
│   ├── api/
│   │   ├── client.ts             # httpx 风格的统一请求封装（携带 traceparent、Idempotency-Key）
│   │   └── generated/            # 从 packages/api-contracts 生成的 TS 类型，不手写
│   ├── lib/                      # 通用工具（本地存储封装、日期格式化等）
│   └── styles/
├── tests/
│   ├── unit/
│   ├── component/
│   └── e2e/                      # Playwright
└── vite.config.ts
```

## 2. 分层规则（对应后端"controller 不含业务规则"的前端版本）

```text
features/*/components   →  只负责渲染 + 触发 hooks 里的 action，不直接调用 api/client
features/*/hooks        →  唯一允许调用 api/client 的地方，用 TanStack Query 包装
features/*/store.ts     →  只放"跨组件共享的瞬时 UI 状态"，服务端数据永远走 TanStack Query 的缓存，不重复放进 Zustand
api/client.ts           →  唯一知道真实后端 URL/header 约定的地方
```

这套分层的目的和后端"domain 不依赖 infrastructure"是同一个精神：把"画面怎么画"和"数据怎么来"分开，方便测试和替换。

## 3. `packages/api-contracts` 在这里的真实用途

这是本项目里第一次真正给这个此前一直空着的 package 找到用途：从各后端 domain 的 OpenAPI（Java 服务）/ Pydantic 模型导出的 schema（Python 服务）生成共享 TypeScript 类型，`employee-portal` 和未来的 `support-console` 共同消费，避免两边各自手写、各自漂移的 DTO 定义。生成脚本本身不属于本 LLD 范围（属于 `packages/api-contracts` 自己的工程实现），这里只声明"本 domain 依赖它，不重复造轮子"。

## 4. 状态管理选型的落地方式

- **服务端数据**（工单状态、会话历史）：TanStack Query，自带缓存/重试/失效策略，天然贴合"前端不自己维护权威状态"的设计原则（BI-EP-004/005）。
- **纯客户端交互态**（turn 状态机、附件上传进度）：Zustand，轻量、不需要为这类瞬时状态引入 Query 的缓存语义。
- **表单**（如果未来有需要显式表单的场景，比如附件描述）：React Hook Form + Zod，校验规则与 `packages/api-contracts` 的类型保持同源。

## 5. 组件设计的一个具体例子：ProposedActionCard

对应视觉稿里"确认，帮我处理"那张卡片：

```text
<ProposedActionCard action={proposedAction}>
  props: action: ProposedAction
  内部状态: 无（纯展示 + 转发点击事件）
  行为:
    onConfirm → 调用 features/conversation/hooks 里的 useConfirmAction(actionId)
    onDecline → 调用 useDeclineAction(actionId)
  渲染规则: action.summary 必须完整展示，不允许 CSS 截断/省略号（BI-EP-007 的组件级落地）
</ProposedActionCard>
```
