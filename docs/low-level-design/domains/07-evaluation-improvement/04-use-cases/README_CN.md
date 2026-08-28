# 04 Use Cases

## UC-EI-001：创建 Golden Dataset

1. Evaluator 创建 dataset draft。
2. 添加 test cases、ground truth、allowed/forbidden tools、approval expectation 和 verification condition。
3. Reviewer 检查 case 是否覆盖 Identity/MFA、Account Lock、Wrong Group、Policy-sensitive request、Duplicate event、Service failure。
4. 发布 dataset version。

## UC-EI-002：运行离线 Benchmark

1. Admin 或 CI 提交 target version、baseline version 和 dataset version。
2. 07 创建 `EvaluationRun`，写入 run key。
3. Runner 调用 Agent Runtime 的 evaluation endpoint，以 mock system state 执行 case。
4. 07 收集 LangSmith experiment、workflow trace、tool trajectory、retrieval evidence 和 ticket outcome。
5. Grader registry 执行 deterministic grader 与必要的 LLM Judge。
6. 生成 score 和 regression report。

## UC-EI-003：Release Gate 判断

1. 07 读取 score、baseline score 和 gate policy。
2. 检查 critical case、policy violation、forbidden tool、root cause、tool argument、verification、cost 和 latency。
3. 输出 `PASSED` 或 `FAILED`。
4. Gate failed 时生成 failure clusters，不允许 candidate promotion。

## UC-EI-004：生成改进候选

1. 07 从失败 case、线上反馈和 reopen trace 中归类 failure cluster。
2. Candidate generator 生成 prompt、routing、tool schema hint、memory retrieval config 或 verification checklist 变更建议。
3. 07 保存 `ImprovementCandidate(DRAFT)`。
4. 候选必须进入 benchmark，不允许直接发布。

## UC-EI-005：候选审批与 Canary

1. Candidate benchmark 通过后，07 请求 06 进行 release approval。
2. 审批通过后，07 向 Runtime/Config owner 发布 candidate approved event。
3. Canary manager 创建小流量 rollout plan。
4. 线上抽样评估通过后扩大流量。
5. 失败时发布 rollback requested。

## UC-EI-006：线上抽样评估

1. 07 消费 workflow completed、ticket reopened、tool failed、approval denied 等事件。
2. 根据 sampling policy 选择 trace。
3. 脱敏后写入 online evaluation queue。
4. 对解释质量、证据完整性、handoff completeness 和 user clarity 做延迟评分。
5. 输出 trend metric，不直接阻塞业务链路。

