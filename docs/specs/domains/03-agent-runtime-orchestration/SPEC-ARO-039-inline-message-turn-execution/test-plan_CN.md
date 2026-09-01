# SPEC-ARO-039 — Test Plan

目标：支撑 `消息轮次内联执行`。

- 单元测试覆盖三选一响应判别逻辑，包括"转人工"和"提出方案"之间的判定边界。
- 与真实 `04-memory-knowledge-service` 的集成测试（对着真实 docker-compose 栈）。
- 幂等重放测试：重复提交同一个 key 不会触发第二次知识检索或 LLM 调用（通过测试替身或真实调用日志的调用次数断言验证）。
- 恢复测试：checkpoint 写入之后、响应返回之前发生崩溃，能从 checkpoint 正确恢复（复用 SPEC-ARO-028 既有的恢复扫描器测试模式）。
