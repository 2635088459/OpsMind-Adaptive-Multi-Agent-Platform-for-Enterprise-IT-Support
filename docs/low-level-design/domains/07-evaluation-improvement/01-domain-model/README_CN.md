# 01 Domain Model

## 聚合边界

07 的核心聚合是 `EvaluationRun` 与 `ImprovementCandidate`。

`EvaluationDataset` 和 `EvaluationTestCase` 是可版本化测试资产。`EvaluationRun` 表示一次对指定系统版本组合的评估执行。`RegressionReport` 表示 candidate 与 baseline 的比较结果。`ImprovementCandidate` 表示可审查、可拒绝、可 Canary、可回滚的受控改进提案。

## 核心实体

### EvaluationDataset

字段语义：

- `datasetId`
- `name`
- `version`
- `domain`
- `scenarioTags`
- `status`
- `caseCount`
- `createdBy`
- `publishedBy`
- `publishedAt`

Dataset 发布后不可原地修改；新增 case 或改 ground truth 必须产生新 version。

### EvaluationTestCase

字段语义：

- `testCaseId`
- `datasetId`
- `caseKey`
- `scenario`
- `userRequest`
- `mockSystemState`
- `groundTruth`
- `allowedTools`
- `forbiddenTools`
- `requiredApproval`
- `verificationCondition`
- `criticality`

`criticality=CRITICAL` 的 case 失败时，release gate 必须失败。

### EvaluationRun

字段语义：

- `runId`
- `runKey`
- `datasetId`
- `datasetVersion`
- `targetVersion`
- `baselineVersion`
- `runtimeVersion`
- `memoryVersion`
- `policyVersion`
- `toolGatewayVersion`
- `status`
- `startedAt`
- `completedAt`
- `triggeredBy`

### EvaluationScore

字段语义：

- `scoreId`
- `runId`
- `testCaseId`
- `dimension`
- `score`
- `passed`
- `threshold`
- `graderType`
- `graderVersion`
- `evidenceRef`
- `failureCode`

### RegressionReport

字段语义：

- `reportId`
- `runId`
- `baselineRunId`
- `candidateVersion`
- `overallDecision`
- `metricDiffs`
- `gateResults`
- `criticalFailures`
- `recommendation`

### ImprovementCandidate

字段语义：

- `candidateId`
- `candidateType`
- `sourceRunId`
- `sourceFailureClusterId`
- `targetComponent`
- `proposedChange`
- `riskLevel`
- `status`
- `createdBy`
- `approvedBy`
- `promotedVersion`

## 值对象

- `EvaluationDimension`：`CLASSIFICATION_ACCURACY`、`ROOT_CAUSE_ACCURACY`、`TOOL_SELECTION`、`TOOL_ARGUMENTS`、`POLICY_COMPLIANCE`、`MEMORY_RETRIEVAL_PRECISION`、`RESOLUTION_SUCCESS`、`REOPEN_RATE`、`HUMAN_ESCALATION_RATE`、`TOKEN_COST`、`LATENCY`、`HANDOFF_COMPLETENESS`。
- `GraderType`：`DETERMINISTIC`、`LLM_JUDGE`、`HYBRID`、`HUMAN_REVIEW`。
- `RunStatus`：`QUEUED`、`RUNNING`、`SCORING`、`COMPARING`、`PASSED`、`FAILED`、`CANCELLED`、`PARTIAL`。
- `CandidateStatus`：`DRAFT`、`BENCHMARKING`、`REJECTED`、`PENDING_APPROVAL`、`APPROVED`、`CANARYING`、`PROMOTED`、`ROLLED_BACK`。

