# SPEC-EI-008 — Dataset Api Access Control

> Domain: Evaluation Improvement
>
> Phase: phase-01-dataset-and-test-assets
>
> Service: `evaluation-improvement-service`
>
> Stack: Python 3.12 / FastAPI / Pydantic / SQLAlchemy / Alembic / PostgreSQL / RabbitMQ / LangSmith SDK / OpenTelemetry / pytest
>
> LLD Mapping: `05-api-contracts, 11-security`
>
> Status: Spec Planning

## Goal

实现 Dataset/TestCase API，并接入 01 identity 的 evaluator/admin role 和 tenant scope。

See `README_CN.md` for the full first-pass specification.
