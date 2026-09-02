# SPEC-SC-015 — Evaluation Comparison Table（评估对比表）

> Domain: `10-support-console` | Phase: 06 — 可观测性界面 | 状态：Spec Planning

## 1. Spec 身份
`SPEC-SC-015`，实现 `UC-SC-07` 的 LangSmith/评估一半，匹配视觉稿中评估/金丝雀对比表部分。

## 2. 目标
嵌入一张评估运行对比表（基线 vs. 金丝雀/候选 agent 行为），数据来源于 `07-evaluation-improvement` 真实的运行/评分模型（phase-02 前已完全关闭，根据项目记忆下一步是 EI-011~013）。

## 3. 设计依据
`01-domain-model` §"EvaluationComparisonTable"；`04-use-cases` UC-SC-07；Agent Observability 视觉稿（LangSmith 对应部分）；domain 07 真实的运行/评分领域模型。

## 4. Actor
（主要是）审查 agent 质量趋势的管理员，或好奇某个特定响应模式为何出现的坐席。

## 5. 范围
拉取相关范围的评估运行数据（例如与本工单类别相同数据集/测试用例的运行，如果存在这种关联）并渲染为对比表（指标列，基线 vs. 候选行）。

## 6. 非目标
任何新的评估/评分逻辑（完全属于 domain 07）——这是一个只读消费者。

## 7. 前置条件
domain 07 有可查询的运行（对全新部署可能为空——空状态是一个有效、预期的渲染）。

## 8. 输入
可选的范围过滤（数据集、运行 ID 范围）。

## 9. 详细行为
从 domain 07 真实的读取端点拉取运行/评分数据并渲染带指标列的表格；用与 SPEC-SC-004 严重程度处理相同的语义色约定，高亮回归项（候选评分比基线差）。

## 10. 交互状态迁移
不适用——一个只读视图。

## 11. 业务不变量
表格必须反映真实的评分数据——接入生产环境后不能有捏造或纯示意性的数字。

## 12. 幂等策略
不适用——是一个 `GET`。

## 13. 消费/依赖的契约
domain 07 真实、已实现的读取端点，直接读取 `interfaces/rest/router.py` 确认（domain 07 的真实进度早已超出某条较早项目记忆"EI-011~013 next"所记录的快照——它自己的真实代码才是事实依据，而非那条已过时的记忆）：`GET /evaluation/runs/{run_id}`、`GET /evaluation/runs/{run_id}/scores`、`GET /evaluation/runs/{run_id}/regression-report`（尚未做过比较时返回真实的 404 `NOT_FOUND`——一个"从未比较过"的真实状态，而非错误）。用于对比的基线 run 从 regression report 自身的 `baseline_run_id` 解析得到，而非 `RunResponse.baseline_version`（一个版本字符串，不是 run id）。

一个本 domain 10 spec 独有的真实发现：这是本前端第一次直接调用一个 Python/FastAPI 后端。其 Pydantic 响应模型没有 `alias_generator`（现场对照真实运行实例、并直接阅读 `schemas.py` 确认）——真实的线上形状确实是 **snake_case**（`run_id`、`baseline_run_id`、`overall_decision`……），而非本应用其他地方所有 Java 服务的 camelCase。本应用自己的类型原样保留，而非悄悄改名，沿用"如实映射真实 DTO"这一贯穿本应用所有功能的一贯做法。

## 14. 安全
与 SPEC-SC-014 的 Tempo 情形不同，这里不需要代理——直接阅读 `interfaces/security.py`确认：本服务真实的调用者身份机制是调用方自行断言的 `X-Actor-Id`/`X-Actor-Role` 请求头对("未来的跨 domain 契约 spec"尚未构建，完全没有 JWT/bearer 校验)，但本 spec 调用的这几个读取端点，其设计本身就有意对完全不声明身份的调用者开放（05-api-contracts 自己的默认读取下限，`EVALUATION_VIEWER`）——现场验证：一次匿名的 `GET .../scores` 调用，每条分数的 `evidence` 都如实返回 `null`（已脱敏），而同一批分数由产出它们的、已认证的 actor 拉取时，则带有真实、完整的 `evidence` 对象。本应用完全不发送任何 actor 请求头，纯粹依赖这一已文档化、本就安全的默认行为——它既不需要、也不向本服务断言任何身份，不引入任何新的伪造面。

本 session 真正新增了 CORS（`CORSMiddleware`，默认空/拒绝，`EVALUATION_CORS_ALLOWED_ORIGINS`）——此前该服务完全没有配置过 CORS（此前的每一个调用方都是服务间调用）。

## 15. 可观测性
拉取时带 `traceparent`，遵循 SPEC-SC-020。

## 16. 错误场景
一次产出了零条分数的 run——一个真实的空状态，与拉取失败明确区分。一个尚无 regression report 的 run（`GET .../regression-report` → 404）只渲染候选侧数据，而非报错。

## 17. 验收场景
一个候选 run 对照一个真实基线 run 比较后，渲染出一张表格，正确可视化标记出真实的按指标回归（候选平均分低于基线平均分）——已对照 fixture 验证，且已对照一个真实运行实例现场验证（真实的一个数据集 → 2 次 run → 真实分数 → 真实比较，全程通过 curl 端到端驱动）。

## 18. 先写测试
针对匹配真实（snake_case）响应形状的 fixture 的组件测试——完整比较含真实回归、尚无基线、真实零分数空状态三种场景；后端针对真实 CORS 行为（允许的源、不允许的源、默认不配置即拒绝）的单元测试。

## 19. 完成定义
表格针对每一种真实状态都能正确渲染 fixture；已对照一个真实运行中的 `evaluation-improvement-service` 实例端到端现场验证——真实的 `RunResponse`/`ScoreResponse`/`RegressionReportResponse` 形状逐字段确认，匿名读取的证据脱敏行为已确认，新增的 CORS 门禁对允许源与不允许源均已确认。
