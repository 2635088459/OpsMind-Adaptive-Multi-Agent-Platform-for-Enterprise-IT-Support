# 13 Package And Class Design

## 技术栈

MVP 推荐：

- Python 3.12
- FastAPI
- SQLAlchemy + Alembic
- Pydantic
- pytest + httpx
- LangSmith Dataset/Experiment
- OpenTelemetry
- RabbitMQ
- PostgreSQL

## 包结构

```text
services/evaluation-improvement-service/
  src/evaluationimprovement/
    domain/
      dataset.py
      test_case.py
      evaluation_run.py
      score.py
      regression_report.py
      improvement_candidate.py
      state_machine.py
      values.py
      events.py
    application/
      commands.py
      views.py
      ports_in.py
      ports_out.py
      services/
        create_dataset.py
        publish_dataset.py
        create_run.py
        execute_case.py
        score_run.py
        compare_regression.py
        evaluate_release_gate.py
        create_improvement_candidate.py
        manage_canary.py
        dispatch_outbox_events.py
    infrastructure/
      persistence/
        postgres/
      langsmith/
        client.py
        dataset_adapter.py
        experiment_adapter.py
      graders/
        deterministic.py
        llm_judge.py
        registry.py
      runtime/
        agent_runtime_client.py
      messaging/
        rabbitmq_consumer.py
        rabbitmq_publisher.py
      security/
        authorization.py
      observability.py
    interfaces/
      rest/
      event/
      admin/
    main.py
    container.py
```

## 端口

- `DatasetRepository`
- `EvaluationRunRepository`
- `ScoreRepository`
- `RegressionReportRepository`
- `ImprovementCandidateRepository`
- `OutboxRepository`
- `ProcessedEventRepository`
- `LangSmithPort`
- `AgentRuntimeEvaluationPort`
- `PolicyApprovalPort`
- `TelemetryArtifactPort`
- `ClockPort`
- `AuthorizationPort`

## Grader Registry

Deterministic graders：

- `ClassificationAccuracyGrader`
- `RootCauseMatchGrader`
- `ToolAllowlistGrader`
- `ForbiddenToolGrader`
- `ToolArgumentSchemaGrader`
- `RequiredApprovalGrader`
- `PolicyComplianceGrader`
- `FinalTicketStateGrader`
- `VerificationConditionGrader`

LLM Judge graders：

- `ExplanationQualityJudge`
- `EvidenceGroundingJudge`
- `HandoffCompletenessJudge`
- `UserInstructionClarityJudge`

安全门禁只读取 deterministic grader 结果。

