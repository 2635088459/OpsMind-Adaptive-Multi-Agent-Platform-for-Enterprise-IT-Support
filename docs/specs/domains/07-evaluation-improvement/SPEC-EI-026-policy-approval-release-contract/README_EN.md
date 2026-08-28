# SPEC-EI-026 — Policy Approval Release Contract

> Domain: Evaluation Improvement
>
> Phase: phase-05-improvement-candidate-lifecycle
>
> Service: `evaluation-improvement-service`
>
> Stack: Python 3.12 / FastAPI / Pydantic / SQLAlchemy / Alembic / PostgreSQL / RabbitMQ / LangSmith SDK / OpenTelemetry / pytest
>
> LLD Mapping: `05-api-contracts, 06-event-contracts, 11-security`
>
> Status: Spec Planning

## Goal

实现 07 向 06 发起 release approval request，并消费 approval granted/denied/expired/cancelled。

See `README_CN.md` for the full first-pass specification.
