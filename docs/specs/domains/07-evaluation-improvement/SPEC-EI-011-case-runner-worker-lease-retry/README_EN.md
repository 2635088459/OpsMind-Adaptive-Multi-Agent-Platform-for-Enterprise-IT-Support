# SPEC-EI-011 — Case Runner Worker Lease Retry

> Domain: Evaluation Improvement
>
> Phase: phase-02-benchmark-run-and-runner
>
> Service: `evaluation-improvement-service`
>
> Stack: Python 3.12 / FastAPI / Pydantic / SQLAlchemy / Alembic / PostgreSQL / RabbitMQ / LangSmith SDK / OpenTelemetry / pytest
>
> LLD Mapping: `04-use-cases, 10-failure-handling`
>
> Status: Spec Planning

## Goal

实现 case runner worker、claim/lease、retry、stale result guard 和 partial run 行为。

See `README_CN.md` for the full first-pass specification.
