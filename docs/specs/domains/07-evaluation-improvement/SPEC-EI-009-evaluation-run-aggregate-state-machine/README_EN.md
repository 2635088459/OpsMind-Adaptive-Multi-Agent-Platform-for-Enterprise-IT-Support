# SPEC-EI-009 — Evaluation Run Aggregate State Machine

> Domain: Evaluation Improvement
>
> Phase: phase-02-benchmark-run-and-runner
>
> Service: `evaluation-improvement-service`
>
> Stack: Python 3.12 / FastAPI / Pydantic / SQLAlchemy / Alembic / PostgreSQL / RabbitMQ / LangSmith SDK / OpenTelemetry / pytest
>
> LLD Mapping: `01-domain-model, 03-state-machine`
>
> Status: Spec Planning

## Goal

实现 EvaluationRun 聚合、run/case 状态机、target/baseline/grader bundle 绑定。

See `README_CN.md` for the full first-pass specification.
