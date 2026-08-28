# 12 Observability

## Metrics

- `evaluation_run_total{status,dataset,target_version}`
- `evaluation_case_total{result,criticality}`
- `evaluation_gate_pass_total{gate_policy}`
- `evaluation_gate_fail_total{reason}`
- `evaluation_score{dimension,dataset,target_version}`
- `evaluation_regression_total{dimension,severity}`
- `improvement_candidate_total{type,status,risk_level}`
- `canary_rollback_total{reason}`
- `grader_error_total{grader_type,grader_version}`
- `evaluation_cost_tokens_total{model,target_version}`
- `evaluation_latency_seconds{stage}`

## Logs

结构化日志必须包含：

- `runId`
- `testCaseId`
- `candidateId`
- `datasetVersion`
- `targetVersion`
- `graderVersion`
- `traceId`
- `correlationId`
- `failureCode`

日志不得包含 raw secret、未脱敏用户文本或工具原始输出。

## Traces

关键 span：

- `EvaluationRunService.createRun`
- `CaseRunner.executeCase`
- `GraderRegistry.grade`
- `RegressionComparator.compare`
- `ReleaseGateEvaluator.evaluate`
- `ImprovementCandidateService.create`
- `CanaryManager.evaluate`

## Alerts

- release gate failed for main branch；
- policy violation > 0；
- forbidden tool call > 0；
- critical case failed；
- judge calibration drift；
- canary rollback condition triggered；
- run stuck in `RUNNING` or `SCORING` beyond SLA。

