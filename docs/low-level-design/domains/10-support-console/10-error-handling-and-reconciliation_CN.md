# Support Console — 错误处理与降级

> **Document ID:** LLD-SC-010
> **Domain:** `10-support-console`
> **状态:** Draft

---

## 1. 三路聚合的部分失败（AiLogEntry 场景）

`03-state-machine` §3.2 已定义 `PARTIAL` 态。具体处理：

```text
ticket timeline 失败    → 整个详情面板不可用（这是核心数据，没有它没法展示工单基本信息）
tool-request 详情失败   → AI 处理记录里对应条目展示"工具执行详情暂时无法加载"，其余条目照常展示
governance audit 失败   → 审批卡片展示"审批历史暂时无法加载"，但如果有 pendingApproval 数据（来自 approval-requests 端点，独立请求）仍然正常展示批准/拒绝按钮
```

三路请求独立降级，不因一路失败而阻塞另外两路——这是本 domain 因为聚合架构（`05-api-contracts` §3）而特有的错误处理复杂度，09 号 domain 没有对应物。

## 2. 队列轮询失败

进入 `DEGRADED`（`03-state-machine` §3.1），继续展示最后一次成功结果并明确提示"数据可能不是最新"，不清空队列列表变成空白页——坐席工作时空白页比"稍微过时的数据"危害更大。

## 3. 审批操作提交失败

- 网络失败/超时：允许坐席重试（同一个 `Idempotency-Key`），不自动重试（审批是高风险操作，是否重试应该由人决定，尤其是不确定上一次请求是否已经真正到达后端时）
- 409 冲突（已被处理）：见 `09-concurrency-and-idempotency` §3，直接展示最新真实状态

## 4. 版本冲突（triage/assign/status-transitions）

见 `09-concurrency-and-idempotency` §2——不是"错误"意义上的失败，是需要坐席决策的正常业务场景，UI 用中性提示而非红色报错样式呈现。

## 5. 明确不做的降级

- 不为"后端整体不可用"设计一个完全离线的坐席工作模式——09 号 domain 的员工兜底路径（直连创建工单）在这里没有对应的合理简化：坐席工作本身就依赖真实、最新的后端数据，离线模式下的坐席操作没有意义，此时正确的做法是明确展示"系统暂时不可用，请稍后再试"，不假装能继续工作。
