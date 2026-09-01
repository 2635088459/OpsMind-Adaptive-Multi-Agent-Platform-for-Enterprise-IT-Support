# Support Console — 测试策略

> **Document ID:** LLD-SC-014
> **Domain:** `10-support-console`
> **状态:** Draft
> **技术基线:** Vitest + React Testing Library + Playwright（与 09 号 domain 共享）

---

## 1. 分层策略与 09 号 domain 一致，不重复展开

见 `09-employee-portal` 的 `14-testing-strategy` §1——单元/组件/契约(MSW)/端到端四层结构相同。

## 2. 本 domain 特有的测试重点：三路聚合的部分失败矩阵

`useAiLog` 有 2³=8 种真实的成功/失败组合（不算完全成功和完全失败，中间 6 种都是需要验证的 `PARTIAL` 场景），必须逐一覆盖：

```text
timeline✓ + tool-request✗ + audit✓  → 展示 timeline+audit，工具执行条目标注"暂时无法加载"
timeline✓ + tool-request✓ + audit✗  → 展示 timeline+tool-request，审批历史标注"暂时无法加载"
... 其余组合类推
timeline✗ + 任意                    → 整个详情面板不可用（timeline 是核心依赖，见 10-error-handling §1）
```

## 3. 并发/冲突场景（对应 `09-concurrency-and-idempotency`）

```text
TEST-SC-01: 两个模拟坐席并发 triage 同一工单，第二个必须收到 409 并进入 VERSION_CONFLICT，不静默覆盖
TEST-SC-02: 审批请求被"另一个坐席"提前处理后，当前坐席再点击批准/拒绝，必须展示"已被处理"而非通用错误
TEST-SC-03: 队列轮询过程中坐席正在编辑筛选条件，新数据到达不重置筛选状态
```

## 4. 端到端场景（Playwright，真实/docker-compose 后端栈）

```text
E2E-SC-01: 真实登录 → 查看队列 → 点击一个工单 → 看到完整 AI 处理记录 → 批准一个真实审批请求
           → 断言 policy-approval-governance 数据库里出现真实的 GRANTED 记录（复用 2026-09-01 集成验证已经证明过的这条链路）
E2E-SC-02: 手动 triage → assign → 状态流转，全程走真实 02-ticket-workflow 端点，断言乐观锁 If-Match 正确传递
E2E-SC-03: 点击"在 Tempo 中打开完整 Trace"，断言生成的深链 URL 包含正确的 traceId
```

## 5. 明确不做的测试（MVP non-goal）

- 不测试真实 Tempo/LangSmith 页面本身的渲染正确性——那是外部系统，本 domain 只断言"深链 URL 拼对了"
- 不做队列轮询在极端并发（数百坐席同时在线）下的性能测试——MVP 阶段坐席规模有限，性能测试留给真实用量增长后再补
