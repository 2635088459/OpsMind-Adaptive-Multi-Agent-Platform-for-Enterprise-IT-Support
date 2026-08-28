# SPEC-EI-017 — Evaluation Score Persistence

> Domain: Evaluation Improvement
>
> Phase: phase-03-graders-and-scoring
>
> Service: `evaluation-improvement-service`
>
> Stack: Python 3.12 / FastAPI / Pydantic / SQLAlchemy / Alembic / PostgreSQL / RabbitMQ / LangSmith SDK / OpenTelemetry / pytest
>
> LLD Mapping: `07-data-model, 08-transaction-and-outbox`
>
> Status: Spec Planning

## Goal

实现 score batch write、failure code、evidence ref、active/superseded score 和 score audit。

See `README_CN.md` for the full first-pass specification.
