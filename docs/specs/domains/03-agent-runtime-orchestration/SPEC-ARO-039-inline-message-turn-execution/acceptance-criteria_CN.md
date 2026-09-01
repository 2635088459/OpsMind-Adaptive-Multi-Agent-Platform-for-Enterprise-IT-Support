# SPEC-ARO-039 — Acceptance Criteria

目标：支撑 `消息轮次内联执行`。

- 每一轮消息都真实调用了 `04-memory-knowledge`——从不被悄悄跳过。
- 响应永远恰好是三种已声明形状之一；从不返回第四种模糊形状。
- 重放同一个 `Idempotency-Key` 返回原始响应，不会第二次真正调用 LLM 或知识检索。
- 每一轮都存在一条 checkpoint，能被既有的 checkpoint 恢复机制（SPEC-ARO-028）正确恢复。
