# Acceptance Criteria — SPEC-EI-001

## 功能验收

- 能完成目标：建立 evaluation-improvement-service 的服务骨架、分层包结构、端口/适配器边界和 07 不执行业务副作用的架构约束。
- 所有状态迁移符合 `13-package-and-class-design, 02-business-invariants` 中定义的规则。
- API、事件、persistence 或 worker 行为有明确 happy path、重复请求、失败路径。
- 不产生 Agent/Prompt/Ticket/Workflow/Tool/Policy/Memory 的直接生产副作用。

## 治理验收

- evaluation fact 保存 source、version、hash、reason/failure code 和 evidence ref。
- 安全门禁由 deterministic grader 或等价确定性规则判定。
- candidate/release 相关行为不能绕过 release gate、06 approval 或 audit。
- sensitive evidence access 和 admin repair 操作可审计。

## 可靠性验收

- 重复请求或重复事件不会产生冲突 final state。
- outbox 事件可重放且 event id 稳定。
- LangSmith/runner/grader/outbox failure 行为可测试。
