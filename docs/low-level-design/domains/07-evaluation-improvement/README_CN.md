# Evaluation Improvement LLD

## 范围

本目录定义 `07-evaluation-improvement` 的低层设计。该域负责离线评估、线上抽样评估、回归比较、发布门禁、失败归因、改进候选生成、Canary 评估和回滚建议。

Evaluation Improvement 不直接修改生产 Agent、Prompt、Policy、Tool Connector 或 Memory。它只回答：一次工作流是否正确、某个版本是否优于基线、是否存在安全/质量回归、哪些改进可以作为候选进入人工审查。

## 核心回答

- Evaluation Dataset 是可版本化测试资产，包含 ticket 场景、mock enterprise state、ground truth、允许/禁止工具、审批期望和验证条件。
- Evaluation Run 是对一个 agent/runtime/memory/policy/tool 版本组合的一次可复现评估。
- Evaluation Score 是按维度输出的评分、证据、阈值判断和失败原因。
- Regression Report 比较 candidate 与 baseline，决定是否通过 release gate。
- Improvement Candidate 是受控改进建议，不是自动上线变更。
- Canary Evaluation 使用线上抽样 trace 和用户反馈判断新版本是否可扩大流量。
- 所有评估输入、版本、grader、阈值和结果必须可追踪、可审计、可复跑。

## 为什么需要独立评估与受控改进域

如果每个服务各自判断“Agent 表现好不好”，会导致：

- 分类、根因、工具选择、审批合规、验证成功率没有统一口径；
- 改 Prompt、改 RAG、改 Memory 可能绕过安全评估；
- 某个指标提升但 policy violation 或 forbidden tool call 同时上升；
- 历史版本无法复现，线上事故无法定位是哪次改动引入；
- 自动改进缺少人工审查、Canary 和回滚边界。

因此 07 是平台质量与演进 owner：它集中管理 dataset、grader、experiment、release gate、regression report 和 improvement lifecycle。

## 与其他域的关系

- `01-user-access-authentication`：提供评估 API 的 actor、role、tenant、service identity 和审计身份；07 不管理用户权限源。
- `02-ticket-workflow`：提供 ticket lifecycle、resolution、reopen、SLA 和用户反馈信号；07 不修改 Ticket state。
- `03-agent-runtime-orchestration`：提供 workflow trace、agent task、prompt/tool trajectory 和候选版本执行入口；07 不直接推进 Workflow state。
- `04-memory-knowledge`：提供 retrieval trace、memory candidate、knowledge source 和 ACL 信号；07 不写 Memory 内容。
- `05-tool-integration-gateway`：提供 tool request/result、forbidden tool、argument schema 和 side-effect verification 信号；07 不执行 Tool。
- `06-policy-approval-governance`：提供 policy decision、approval latency、violation、override 和 release approval；07 不能绕过 06 发布候选。
- `08-observability-platform`：汇聚 trace、metrics、logs，并承载 dashboard/alert；07 输出 evaluation metrics 和 reports。

## 14 个 LLD 切面

1. `01-domain-model`：Dataset、Test Case、Run、Score、Regression Report、Improvement Candidate。
2. `02-business-invariants`：安全门禁、不可自动上线、版本可复现、评估不可改写。
3. `03-state-machine`：Dataset、Evaluation Run、Improvement Candidate、Canary 状态机。
4. `04-use-cases`：创建 dataset、运行 benchmark、比较回归、生成候选、审批发布、Canary、回滚。
5. `05-api-contracts`：Dataset API、Run API、Report API、Candidate API、Admin API。
6. `06-event-contracts`：消费 workflow/ticket/tool/policy/memory 事件，发布 evaluation/candidate 事件。
7. `07-data-model`：PostgreSQL 表、唯一键、JSONB score、artifact 引用、保留策略。
8. `08-transaction-and-outbox`：run/score/report/candidate/audit/outbox 事务边界。
9. `09-concurrency-and-idempotency`：重复触发、并行 run、baseline race、candidate promotion race。
10. `10-failure-handling`：grader failure、LangSmith outage、dataset corruption、partial run、poison event。
11. `11-security`：评估数据脱敏、角色权限、候选发布审批、prompt/tool 变更隔离。
12. `12-observability`：score trends、regression alerts、gate failures、cost/latency、judge drift。
13. `13-package-and-class-design`：service、ports/adapters、LangSmith adapter、grader registry、report generator。
14. `14-testing-strategy`：unit、integration、contract、security、regression、golden dataset tests。

## MVP 冻结原则

- MVP 使用 LangSmith Dataset + Experiment 作为离线评估系统，PostgreSQL 保存本域事实和 release gate 结果。
- 关键安全规则只能由 deterministic grader 判定，不能只依赖 LLM-as-Judge。
- `policy_violation_count` 与 `forbidden_tool_call_count` 必须为 0，否则 candidate 不能进入发布审批。
- Evaluation 可以生成 improvement candidate，但生产发布必须经过人工审批和 release gate。
- Evaluation failure 不阻塞线上 ticket 处理，但阻塞 candidate promotion。

