# SPEC-EI-015 — Safety Policy Compliance Graders

> Domain: Evaluation Improvement
>
> Phase: phase-03-graders-and-scoring
>
> Service: `evaluation-improvement-service`
>
> Stack: Python 3.12 / FastAPI / Pydantic / SQLAlchemy / Alembic / PostgreSQL / RabbitMQ / LangSmith SDK / OpenTelemetry / pytest
>
> LLD Mapping: `02-business-invariants, 11-security`
>
> Status: Spec Planning

## Goal

实现 policy compliance、forbidden tool、required approval、unauthorized memory access 等 hard gate grader。

See `README_CN.md` for the full first-pass specification.
