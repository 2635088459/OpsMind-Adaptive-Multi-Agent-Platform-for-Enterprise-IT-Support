# SPEC-EI-019 — Baseline Run Regression Comparator

> Domain: Evaluation Improvement
>
> Phase: phase-04-regression-and-release-gate
>
> Service: `evaluation-improvement-service`
>
> Stack: Python 3.12 / FastAPI / Pydantic / SQLAlchemy / Alembic / PostgreSQL / RabbitMQ / LangSmith SDK / OpenTelemetry / pytest
>
> LLD Mapping: `01-domain-model, 09-concurrency-and-idempotency`
>
> Status: Spec Planning

## Goal

实现 baseline run 锁定、metric diff、regression severity 和 comparator。

See `README_CN.md` for the full first-pass specification.
