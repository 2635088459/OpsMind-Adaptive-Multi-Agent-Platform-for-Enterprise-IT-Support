# OpsMind Support Console — 实施路线图

> **Document ID:** IMP-SC-000
> **Domain:** `10-support-console`
> **文档类型:** 实施路线图
> **版本:** 1.0
> **状态:** Draft
> **交付方法:** Spec-Driven Development + Test-Driven Development + Vertical Slice Delivery
> **设计基线:** `docs/low-level-design/domains/10-support-console/`
> **代码目录:** `apps/support-console/`
> **Feature Spec 目录:** `docs/specs/domains/10-support-console/`
> **可追溯性目录:** `docs/traceability/domains/10-support-console/`

---

# 1. 目的

把已批准的 Support Console LLD（14 篇）转化为可执行实施计划，不重新设计、不重复 14 篇内容。

---

# 2. 评审决定

## 2.1 与 employee-portal 路线图的关系

Phase 结构同样是 00-09，共 10 个 phase，但内容分布不同——本 domain 的最大工程复杂度在 Phase 03（三路聚合与部分降级），不在登录/对话这类 09 号 domain 的重心上。

## 2.2 目录
```text
docs/implementation-plans/domains/10-support-console/
docs/specs/domains/10-support-console/
docs/traceability/domains/10-support-console/
apps/support-console/
```

## 2.3 技术基线
与 `09-employee-portal` 完全一致（继承 shared technology-baseline，不重复列出）。

## 2.4 一个重要的范围优势

与 09 号 domain 不同，本 domain **绝大多数依赖的后端能力已经真实建成**（`01-domain-model` §1 已列出确认清单）。这意味着 Phase 02-05 可以直接对接真实端点，不需要像 09 号 domain 那样大量依赖 contract-first mock——只有 Phase 06（可观测性/评测）和队列实时推送涉及尚待确认/新增的部分。

---

# 3. 设计基线

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
- 绕开 BI-SC-001~006
- 在版本冲突场景静默覆盖他人的修改（BI-SC-005）
- 让审批卡片的批准/拒绝走乐观 UI（BI-SC-002）
- 在前端伪造 `06-policy-approval-governance` 目前并不真正执行的细粒度授权（`11-security-and-authorization` §2 已如实记录这个现状缺口）

---

# 4. Phase 总览

```text
Phase 00  工程基础
Phase 01  登录与会话切片（复用 09 号 domain 已验证的机制）
Phase 02  分诊队列视图切片
Phase 03  工单详情与 AI 处理记录聚合切片
Phase 04  审批决策切片
Phase 05  手动分诊/指派/流转切片
Phase 06  可观测性与评测页面切片
Phase 07  并发与冲突处理强化
Phase 08  安全与可观测性强化
Phase 09  发布就绪
```

---

# 5. Phase 00 — 工程基础

## 目标
`apps/support-console/` Vite + React 19 + TS 脚手架，路由骨架（分诊队列/待审批/我负责的/仪表盘/可观测性），CI。

## 交付物
- 复用 `packages/api-contracts` 已有生成流程（09 号 domain Phase 00 已经建立），追加 05/06/07 号 domain 的类型生成
- 路由骨架 + 未登录态空白首页
- CI：lint + typecheck + `vitest run`

## 退出标准
同 `09-employee-portal` Phase 00 的退出标准结构，不重复列出。

详细计划：`phase-00-engineering-foundation_CN.md`

---

# 6. Phase 01 — 登录与会话切片

## 目标
复用 `01-user-access-authentication` 真实机制，验证坐席角色的真实 scope（`ticket:triage`/`ticket:assign` 等）能正确携带。

## Feature Specs
```text
SPEC-SC-001-oidc-login-redirect
SPEC-SC-002-role-scope-verification
```

## 为什么比 09 号 domain 快
登录机制本身已经被 09 号 domain Phase 01 验证过一次（同一个 Keycloak realm，同一套 PKCE 流程），本 phase 主要验证的是**不同 scope 的坐席账号**能否正确访问坐席专属端点，不是重新摸索登录本身。

---

# 7. Phase 02 — 分诊队列视图切片

## 目标
```text
坐席登录
→ 看到自己团队的队列
→ 按严重程度排序，SLA 即将超时的高亮
```

## Feature Specs
```text
SPEC-SC-003-queue-list
SPEC-SC-004-severity-and-sla-display
SPEC-SC-005-queue-polling
```

## 关键要求
- 直接对接真实 `02-ticket-workflow` 队列查询端点，不需要 mock
- 严重程度/SLA 展示严格来自后端字段（BI-SC-004）

---

# 8. Phase 03 — 工单详情与 AI 处理记录聚合切片

## 目标
```text
点击队列一行
→ 并发拉取 timeline + tool-request 详情 + governance audit
→ 合并渲染成一条 AiLogEntry 时间线
→ 任一路失败时进入 PARTIAL，而非整体报错
```

## 为什么是本 domain 的核心 phase
`useAiLog`（`13-package-and-class-design` §2）是本 domain 架构复杂度最高的部分，也是"坐席能审查 AI 做过什么"这一核心产品定位的直接实现。

## Feature Specs
```text
SPEC-SC-006-ai-log-aggregation
SPEC-SC-007-partial-degradation-states
```

## 关键要求
- 2³ 种成功/失败组合矩阵全部有测试覆盖（`14-testing-strategy` §2）
- 三路请求各自独立的 loading/error 状态，不合并成一个笼统的 loading

---

# 9. Phase 04 — 审批决策切片

## 目标
```text
工单详情面板展示待审批请求
→ 坐席批准/拒绝
→ 卡片转为只读历史（不可逆）
```

## Feature Specs
```text
SPEC-SC-008-approval-card
SPEC-SC-009-grant-deny-action
```

## 关键要求
- 批准/拒绝走非乐观 UI（BI-SC-002），等待后端真实确认
- 直接复用 2026-09-01 集成验证已经真实跑通的 `06-policy-approval-governance` grant/deny 端点——这是本 phase 里"后端能力已就绪、只做前端"的又一个例子

---

# 10. Phase 05 — 手动分诊/指派/流转切片

## 目标
坐席手动介入（AI 未处理、处理失败、或需要人工调整）时的直接操作能力。

## Feature Specs
```text
SPEC-SC-010-manual-triage
SPEC-SC-011-manual-assign
SPEC-SC-012-manual-status-transition
SPEC-SC-013-version-conflict-handling
```

## 关键要求
- 每个操作携带 If-Match 版本号
- 版本冲突时进入 `VERSION_CONFLICT`（BI-SC-005），不静默覆盖——本 phase 的验收重点是"冲突处理是否真的可见、可操作"，不是"操作本身能否成功"（后者在没有并发的情况下已经很简单）

---

# 11. Phase 06 — 可观测性与评测页面切片

## 目标
```text
Trace 瀑布图预览 + "在 Tempo 中打开"外链
评测/灰度对比表 + "在 LangSmith 中查看"外链
```

## Feature Specs
```text
SPEC-SC-014-trace-waterfall-preview
SPEC-SC-015-evaluation-comparison-table
```

## 关键要求
- 对接 `07-evaluation-improvement` 真实的版本对比数据（具体字段以该 domain 自己文档为准）
- Tempo 深链 URL 拼接正确性有专门测试（`14-testing-strategy` §4 E2E-SC-03）
- 本 phase 明确是只读展示，不提供任何可观测性系统的写操作入口

---

# 12. Phase 07 — 并发与冲突处理强化

## 目标
把 `09-concurrency-and-idempotency` 定义的多坐席协作场景系统性验证一遍，不是零散地在各 phase 里顺带测。

## Feature Specs
```text
SPEC-SC-016-concurrent-triage-conflict
SPEC-SC-017-concurrent-approval-conflict
```

---

# 13. Phase 08 — 安全与可观测性强化

## 目标
同 `09-employee-portal` Phase 08 的结构，内容对应本 domain 自己的 `11-security-and-authorization`/`12-observability-and-audit`。

## Feature Specs
```text
SPEC-SC-018-scope-hardening
SPEC-SC-019-partial-authorization-visibility
SPEC-SC-020-trace-propagation-coverage
```

---

# 14. Phase 09 — 发布就绪

## 目标
E2E-SC-01~03 全部通过，性能/可访问性基础检查，发布门禁清单。

## 为什么最后
依赖前面所有 phase，且 E2E-SC-01 直接复用 2026-09-01 集成验证已经证明可行的真实 ticket-workflow ↔ policy-approval-governance 链路，是对整套架构假设的最终验证。

---

# 15-20. 标准结构、可追溯性、质量门禁

与 `09-employee-portal` 路线图 §16-19 完全一致的模板（Standard Phase Plan Structure / Standard Feature Spec Structure / Traceability / Cross-phase Quality Gates），不重复排版，实施时直接复用该文档的对应章节。

---

# 21. MVP 边界

```text
Phase 00 → 01 → 02 → 03 → 04 → 05
```

Phase 06、07、08、09 可标注为生产化延伸。

演示至少应展示：
```text
登录 → 查看队列 → 点开一个工单看 AI 处理记录 → 批准一个真实审批请求
```

---

# 22. 立即下一步

```text
1. 评审本路线图
2. 编写 phase-00-engineering-foundation_CN.md
3. 搭建 Phase 00 工程基础
4. 编写 SPEC-SC-001-oidc-login-redirect_CN.md
5. 进入 Phase 01 的 RED 阶段
```
