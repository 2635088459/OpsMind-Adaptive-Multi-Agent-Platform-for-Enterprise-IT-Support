# OpsMind Employee Portal — 实施路线图

> **Document ID:** IMP-EP-000
> **Domain:** `09-employee-portal`
> **文档类型:** 实施路线图
> **版本:** 1.0
> **状态:** Draft
> **交付方法:** Spec-Driven Development + Test-Driven Development + Vertical Slice Delivery
> **设计基线:** `docs/low-level-design/domains/09-employee-portal/`
> **代码目录:** `apps/employee-portal/`
> **Feature Spec 目录:** `docs/specs/domains/09-employee-portal/`
> **可追溯性目录:** `docs/traceability/domains/09-employee-portal/`

---

# 1. 目的

本文档把已批准的 Employee Portal LLD（14 篇）转化为可执行、面向生产的实施计划。

它回答：

```text
既然设计意图已经明确，
先做什么、为什么按这个顺序做、
下一个 phase 开始前必须先完成什么？
```

不重新设计 employee-portal，不重复 14 篇 LLD 的内容。

---

# 2. 评审决定

## 2.1 Phase 结构

Phase 00–09 顺序，共 10 个 phase。比后端 `02-ticket-workflow` 的 Phase 00-10 少一个，原因：前端没有独立的"安全/审计强化"和"混沌/对账"两个大 phase——这两类工作在前端体量小得多，合并进 Phase 08（安全与可观测性强化）和 Phase 07（韧性与错误处理强化），不单独立项。

## 2.2 目录

```text
docs/implementation-plans/domains/09-employee-portal/
docs/specs/domains/09-employee-portal/
docs/traceability/domains/09-employee-portal/
apps/employee-portal/
```

## 2.3 技术基线

冻结（继承 shared technology-baseline，不重新选型）：

```text
TypeScript
React 19
Vite 8.x
Node.js 24 LTS
pnpm
React Router
TanStack Query
Zustand
React Hook Form + Zod
Tailwind CSS + shadcn/ui
Vitest + React Testing Library + Playwright
SSE（非 WebSocket）
```

## 2.4 Phase 00 范围

Phase 00 建立工程基础（脚手架、路由骨架、CI、真实 Keycloak 登录跳转的最小验证），不实现任何对话/工单业务行为。

## 2.5 跨域依赖的处理策略

`01-domain-model`/`04-use-cases`/`05-api-contracts` 已经标出三处后端尚未建成的能力：

```text
1. 对话轮次端点          → 03-agent-runtime-orchestration（待立项）
2. 工单状态 SSE 推送      → 02-ticket-workflow（待新增）
3. 附件上传共享能力       → 新的独立共享能力（待立项）
```

按 `02-ticket-workflow` 自己 roadmap 里已经验证有效的 **Contract-first Cross-domain Integration Policy**（见 §9）：不等待这三个后端能力真正建成，用已声明的契约形状 + 确定性 mock（MSW）先把前端行为锁定，真实服务就绪后切换为兼容性测试。这意味着 Phase 02-05 可以和后端并行推进，不互相阻塞。

---

# 3. 设计基线

以下 Employee Portal LLD 已完成：

```text
01-domain-model
02-business-invariants
03-state-machine
04-use-cases
05-api-contracts
06-event-contracts
07-data-model
08-transaction-and-outbox
09-concurrency-and-idempotency
10-error-handling-and-reconciliation
11-security-and-authorization
12-observability-and-audit
13-package-and-class-design
14-testing-strategy
```

实施不得：

- 绕开 BI-EP-001~007 任何一条业务不变量
- 在前端本地伪造/推断工单状态（BI-EP-004/005）
- 把 ProposedAction 的执行前提完全交给前端判断，绕过后端二次校验（BI-EP-003）
- 静默丢弃用户已输入但未发送的草稿（BI-EP-006）
- 让 `packages/api-contracts` 之外的地方手写后端 DTO 类型

---

# 4. Phase 总览

```text
Phase 00  工程基础
Phase 01  登录与会话切片
Phase 02  对话核心切片（发消息 → 收到文本回复）
Phase 03  自助方案确认切片（ProposedAction）
Phase 04  证据文件切片（附件上传）
Phase 05  转人工与工单进展面板切片
Phase 06  解决确认与重开切片
Phase 07  韧性与错误处理强化
Phase 08  安全与可观测性强化
Phase 09  发布就绪
```

全部 phase 属于 `09-employee-portal`，代码位于 `apps/employee-portal/`。

---

# 5. 跨切面能力的最低基线

从 Phase 01 起，每个 Feature Spec 检查：

```text
真实 OIDC 会话状态
Idempotency-Key（发消息/确认方案等副作用请求）
错误处理（网络失败/超时/5xx 的用户可见反馈）
Trace 透传（traceparent header）
单元/组件/端到端测试
```

Phase 08 不修补前面 phase 完全遗漏的安全/可观测性代码——Phase 08 专注于收尾和强化。

---

# 6. Phase 00 — 工程基础

## 目标
搭建 SDD/TDD 所需的工程与测试基础，不实现任何业务行为。

## 主要设计依据
```text
13-package-and-class-design
14-testing-strategy
technology-baseline
```

## 交付物
- `apps/employee-portal/` Vite + React 19 + TypeScript 脚手架
- `packages/api-contracts` 的最小可用生成流程（先给 01-user-access-authentication、02-ticket-workflow 两个已建成 domain 生成类型）
- 路由骨架（`app/router.tsx`），含一个未登录态的空白首页
- CI：lint + typecheck + `vitest run`
- 指向真实 `infrastructure/docker-compose/full-platform.yml` 栈的 Playwright 基础配置（可以只跑"页面能打开"这一条冒烟测试）

## 退出标准
```text
pnpm build 通过
pnpm test 通过（哪怕测试数量很少）
CI 跑通
不存在任何 Conversation/Message/Ticket 业务代码
```

详细计划：`phase-00-engineering-foundation_CN.md`

---

# 7. Phase 01 — 登录与会话切片

## 目标
完整实现 UC-EP-01 的登录前置条件：真实 Keycloak Authorization Code + PKCE 登录跳转 → 回调 → `AUTHENTICATED` 会话态。

## 为什么现在做
后面所有 phase 都需要一个真实登录会话；且这个流程已经在 2026-09-01 的项目级集成验证里对同一个 Keycloak realm 真实跑通过（`project-level-integration-verification` memory），复用已验证的契约，不是从零摸索。

## Feature Specs
```text
SPEC-EP-001-oidc-login-redirect
SPEC-EP-002-session-state-machine
SPEC-EP-003-draft-preservation-on-expiry
```

## 退出标准
- 真实浏览器跳转到 Keycloak，输入真实测试账号密码后能回到门户并处于 `AUTHENTICATED`
- Token 临近过期时静默刷新，刷新失败时正确进入 `SESSION_EXPIRED`（`03-state-machine` §3.3）
- 会话过期打断输入时，草稿被写入 `localStorage`（BI-EP-006），重新登录后恢复

---

# 8. Phase 02 — 对话核心切片

## 目标
```text
已登录员工
→ 发送第一条消息
→ 收到 agent 纯文本回复
```

## 为什么现在做
这是员工门户的入口体验，验证 `Conversation`/`Message` 领域模型、Turn 状态机、以及 §2.5 提到的 Contract-first 策略本身是否可行。

## Feature Specs
```text
SPEC-EP-004-create-conversation
SPEC-EP-005-send-message
SPEC-EP-006-turn-state-machine
```

## 关键要求
- 使用 MSW mock `05-api-contracts` §2.1/§2.2 声明的契约，`03-agent-runtime-orchestration` 真实端点就绪后追加兼容性测试，不重写前端逻辑
- 重复点击发送不产生两条重复消息（`09-concurrency-and-idempotency` §1）
- Turn 状态机的每一个状态迁移都有对应的组件测试

---

# 9. Phase 03 — 自助方案确认切片

## 目标
```text
agent 回复带 ProposedAction
→ 员工确认或拒绝
→ 确认后展示执行进度直至完成
```

## Feature Specs
```text
SPEC-EP-007-proposed-action-card
SPEC-EP-008-confirm-action
SPEC-EP-009-decline-action
```

## 关键要求
- `ProposedActionCard` 的方案说明不允许截断（BI-EP-007）
- 确认后按钮立即禁用，`actionId` 不可重复触发真实执行（`09-concurrency-and-idempotency` §3）
- 执行失败时展示的下一步是 agent 自己决定的（新建议或转人工），前端不擅自重试

---

# 10. Phase 04 — 证据文件切片

## 目标
员工可以在消息里附带照片/文件，作为 agent 分析的依据。

## 为什么在这里
早于"转人工"切片，因为视觉稿里两个场景（MFA 问题、屏幕摔裂）都依赖附件能力，且附件状态机（`03-state-machine` §3.2）相对独立，可以单独验证。

## Feature Specs
```text
SPEC-EP-010-attachment-upload
SPEC-EP-011-attachment-validation
```

## 关键要求
- 依赖新的独立共享附件能力（尚未立项，见 §2.5）——本 phase 同样走 contract-first：先对着声明的契约 mock，真实共享能力就绪后再切换
- 单个附件失败不阻塞其他附件/文字发送（`10-error-handling-and-reconciliation` §2.2）
- 只有 `READY` 状态的附件允许出现在已发送消息里（BI-EP-002）

---

# 11. Phase 05 — 转人工与工单进展面板切片

## 目标
```text
agent 判断无权限处理
→ 自动创建真实工单
→ 工单状态面板出现并持续展示真实进展
```

## 为什么现在做
这是员工门户里唯一真正触达 `02-ticket-workflow` 真实数据的场景，也是产品视觉稿里最重要的差异化体验。

## Feature Specs
```text
SPEC-EP-012-escalation-notice
SPEC-EP-013-ticket-status-panel
SPEC-EP-014-ticket-status-sse
SPEC-EP-015-resume-conversation
```

## 关键要求
- `EscalationNotice` 携带的 `ticketId` 必须是 `02-ticket-workflow` 真实签发的（`04-use-cases` UC-EP-04 验收标准）
- SSE 端点同样走 contract-first：`02-ticket-workflow` 目前只有 REST 读取，本 phase 先对着 `06-event-contracts` §2.1 声明的形状 mock，真实推送端点就绪后再对接
- 断线重连使用 `Last-Event-ID`，不倒退状态机步骤高亮（`09-concurrency-and-idempotency` §4）
- UC-EP-06：重新打开门户直接展示最近一次活跃/已升级会话，不是空白页

---

# 12. Phase 06 — 解决确认与重开切片

## 目标
工单到达 `RESOLVED` 后，员工在门户内确认解决，或标记未解决触发重开。

## 为什么现在做
闭环产品视觉稿里"进展面板"的最后一步；且依赖的后端能力（`SPEC-TW-026-confirm-resolution`、reopen 相关端点）已经是 `02-ticket-workflow` 真实建成的能力，不需要等待任何新契约。

## Feature Specs
```text
SPEC-EP-016-confirm-resolution
SPEC-EP-017-reopen-from-portal
```

## 关键要求
- 直接对接真实端点，不需要 MSW mock 阶段——这是本 domain 里第一个"全链路都是已建成能力"的 phase，可以作为验证整套架构假设的里程碑

---

# 13. Phase 07 — 韧性与错误处理强化

## 目标
把 `10-error-handling-and-reconciliation` 里定义的完整降级矩阵落地，而不是散落在各个 phase 里零星处理。

## 范围
```text
Agent 不可用 → 直连 ticket-workflow 兜底创建工单（降级路径 1）
彻底离线 → 保留草稿 + 明确提示（降级路径 2）
SSE 断线重连 + 轮询降级
附件上传失败重试
会话过期与请求中途失败的交叉场景
```

## Feature Specs
```text
SPEC-EP-018-agent-unavailable-fallback
SPEC-EP-019-offline-degradation
SPEC-EP-020-sse-reconnect-hardening
```

---

# 14. Phase 08 — 安全与可观测性强化

## 目标
完成并强化各 phase 已经引入的安全/可观测性能力。

## 范围
```text
新 scope（conversations:create/message/confirm-action）接入验证
附件安全校验的前端侧完整落地
XSS 防护审计（agent 文本渲染路径全覆盖）
traceparent 透传覆盖所有 API 调用
客户端错误上报接入（可选，MVP 可降级为本地埋点）
```

## Feature Specs
```text
SPEC-EP-021-scope-hardening
SPEC-EP-022-xss-audit
SPEC-EP-023-trace-propagation-coverage
```

---

# 15. Phase 09 — 发布就绪

## 目标
证明整条黄金路径可以在真实（或 docker-compose 起的）后端栈上端到端跑通。

## 范围
```text
E2E-EP-01~04（14-testing-strategy §3.2 已定义）全部通过
可访问性（键盘导航、屏幕阅读器基本可用性）基础检查
性能预算（首屏、发消息到收到回复的前端可感知延迟）
发布门禁（release gate）清单
```

## 为什么最后
依赖前面所有 phase 的真实能力全部就位，且需要 `03-agent-runtime-orchestration`/`02-ticket-workflow` 对应的新增后端能力已经真实建成（不再是 mock）。

---

# 16. 标准 Phase 计划结构

每个 phase 计划包含：

```text
1. 目标
2. 为什么是现在
3. 设计依据
4. 包含的 Feature Specs
5. 范围
6. 非目标
7. 已应用的架构决定
8. TDD 执行顺序
9. 实施任务
10. 测试计划
11. 交付物
12. 风险
13. 退出标准
14. 可追溯性更新
```

---

# 17. 标准 Feature Spec 结构

```text
1. Spec 身份
2. 目标
3. 设计依据
4. Actor
5. 范围
6. 非目标
7. 前置条件
8. 输入
9. 详细行为
10. 交互状态迁移
11. 业务不变量
12. 幂等策略
13. 消费/依赖的契约
14. 安全
15. 可观测性
16. 错误场景
17. 验收场景
18. 先写测试
19. 完成定义
```

---

# 18. 可追溯性

维护：
```text
docs/traceability/domains/09-employee-portal/traceability-matrix.yaml
```

示例：
```yaml
SPEC-EP-005:
  phase: Phase-02
  design:
    use_cases: [UC-EP-02]
    api: ["POST /api/v1/conversations/{id}/messages"]
    invariants: [BI-EP-002]
  implementation:
    components: [MessageComposer, useSendMessage]
  tests:
    - SendMessage.test.tsx
    - useSendMessage.test.ts
    - E2E-EP-01
```

---

# 19. 跨 Phase 质量门禁

每个 phase 要求：

```text
已评审的 Feature Spec
测试先于或随实现一起提交
关键不变量有覆盖
无未批准的破坏性 API 变更
真实后端集成测试（对已建成能力）或明确标注的 mock（对待建能力）
不在日志/trace 中出现敏感信息
更新的可追溯性矩阵
更新的 README 与运行说明
```

---

# 20. MVP 边界

```text
Phase 00 → 01 → 02 → 03 → 05 → 06
```

Phase 04（附件）、07、08、09 可以标注为：
```text
MVP 期望具备
生产化延伸
```

演示至少应展示：
```text
登录 → 发消息 → agent 分析并提出方案 → 确认执行 → 完成
      → 另一条消息 → agent 判断无权限 → 自动转工单 → 进展面板
```

---

# 21. 立即下一步

```text
1. 评审本路线图
2. 编写 phase-00-engineering-foundation_CN.md
3. 确认目录与技术基线
4. 搭建 Phase 00 工程基础
5. 满足 Phase 00 退出标准
6. 编写 SPEC-EP-001-oidc-login-redirect_CN.md
7. 进入 Phase 01 的 RED 阶段
```
