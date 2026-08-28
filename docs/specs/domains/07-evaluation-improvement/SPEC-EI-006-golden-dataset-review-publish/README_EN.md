# SPEC-EI-006 — Golden Dataset Review Publish

> Domain: Evaluation Improvement
>
> Phase: phase-01-dataset-and-test-assets
>
> Service: `evaluation-improvement-service`
>
> Stack: Python 3.12 / FastAPI / Pydantic / SQLAlchemy / Alembic / PostgreSQL / RabbitMQ / LangSmith SDK / OpenTelemetry / pytest
>
> LLD Mapping: `02-business-invariants, 11-security`
>
> Status: Spec Planning

## Goal

实现 golden dataset 的 review/publish/deprecate 流程，保证 published dataset 不可变。

See `README_CN.md` for the full first-pass specification.
