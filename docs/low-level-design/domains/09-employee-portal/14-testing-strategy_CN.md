# Employee Portal — 测试策略

> **Document ID:** LLD-EP-014
> **Domain:** `09-employee-portal`
> **状态:** Draft
> **技术基线:** Vitest + React Testing Library + Playwright（shared baseline §15，冻结）

---

## 1. 分层测试策略

```text
单元测试 (Vitest)         → 纯函数、hooks 逻辑（不含渲染）、store 的状态迁移
组件测试 (RTL)            → 单个组件的渲染/交互行为，mock 掉 api/client
契约测试 (MSW)            → 针对 packages/api-contracts 生成的类型做 mock server，验证前端请求/解析逻辑
端到端测试 (Playwright)   → 真实浏览器，对接真实（或 docker-compose 起的）后端栈
```

## 2. 契约优先：对"待建能力"也要先写测试

呼应 `02-ticket-workflow` 自己的 roadmap 里"Contract-first Cross-domain Integration Policy"——`05-api-contracts` §2 列出的对话轮次端点虽然后端还没建，但前端的契约测试**现在就可以写**：用 MSW 按照已声明的契约形状 mock 一个确定性的服务端，先把前端行为锁定，等 `03-agent-runtime-orchestration` 真正建成后再切换成"针对真实服务的兼容性测试"，验证真实响应符合同一份契约。

## 3. 关键场景的测试清单

### 3.1 组件级
- `ProposedActionCard`：`summary` 完整展示不截断（BI-EP-007）；确认后按钮立即禁用，防止重复点击
- 附件上传组件：`uploadStatus` 为 `uploading`/`failed` 时，父级发送按钮必须禁用（BI-EP-002）
- 工单状态面板：状态步进器（stepper）只能前进，SSE 收到"更旧"的 `updatedAt` 时组件不倒退（呼应 `09-concurrency-and-idempotency` §4）

### 3.2 端到端（Playwright，针对真实/docker-compose 后端栈）
```text
E2E-EP-01: 真实 Keycloak 登录 → 发一条消息 → 收到 agent 文本回复
E2E-EP-02: 发消息 → 收到 ProposedAction → 确认 → 看到执行完成状态
E2E-EP-03: 发消息 → 收到 EscalationNotice → 工单面板出现并展示真实状态机进展
E2E-EP-04: 断网重连 → 草稿不丢失（BI-EP-006）→ 重新登录后恢复草稿
```

E2E-EP-01 应该复用本项目 2026-09-01 集成验证里已经验证过的真实 Keycloak Authorization Code + PKCE 流程（`project-level-integration-verification` memory），不需要重新摸索登录自动化的做法。

### 3.3 幂等/并发（对应 `09-concurrency-and-idempotency`）
- 同一条消息因网络重试发送两次，断言后端只收到一次真实处理（通过 mock 服务端校验 `Idempotency-Key` 复用）
- 同一个 `actionId` 重复确认，断言第二次请求不触发新的执行副作用

## 4. 明确不做的测试（MVP non-goal）

- 不做视觉回归测试（screenshot diff）——MVP 阶段视觉细节变动频繁，投入产出比低，留给产品视觉稳定后再引入
- 不做多标签页并发的自动化测试（`09-concurrency-and-idempotency` §2 已经说明这是刻意简化的场景，不值得为此建自动化测试基础设施）
- 不测试 LangSmith/OTel 的实际数据上报正确性——那是 `03-agent-runtime-orchestration`/`08-observability-platform` 自己的测试职责，前端只需断言 `traceparent` header 确实被生成并携带，不断言后端如何处理它
