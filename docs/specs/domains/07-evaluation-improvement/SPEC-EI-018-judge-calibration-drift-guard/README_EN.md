# SPEC-EI-018 — Judge Calibration Drift Guard

> Domain: Evaluation Improvement
>
> Phase: phase-03-graders-and-scoring
>
> Service: `evaluation-improvement-service`
>
> Stack: Python 3.12 / FastAPI / Pydantic / SQLAlchemy / Alembic / PostgreSQL / RabbitMQ / LangSmith SDK / OpenTelemetry / pytest
>
> LLD Mapping: `12-observability, 14-testing-strategy`
>
> Status: Spec Planning

## Goal

实现 judge calibration set、drift detection、grader bundle version 和 drift alert。

See `README_CN.md` for the full first-pass specification.
