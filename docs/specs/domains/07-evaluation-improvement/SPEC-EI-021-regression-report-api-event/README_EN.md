# SPEC-EI-021 — Regression Report Api Event

> Domain: Evaluation Improvement
>
> Phase: phase-04-regression-and-release-gate
>
> Service: `evaluation-improvement-service`
>
> Stack: Python 3.12 / FastAPI / Pydantic / SQLAlchemy / Alembic / PostgreSQL / RabbitMQ / LangSmith SDK / OpenTelemetry / pytest
>
> LLD Mapping: `05-api-contracts, 06-event-contracts`
>
> Status: Spec Planning

## Goal

实现 regression report persistence/API，以及 gate passed/failed/regression detected 事件。

See `README_CN.md` for the full first-pass specification.
