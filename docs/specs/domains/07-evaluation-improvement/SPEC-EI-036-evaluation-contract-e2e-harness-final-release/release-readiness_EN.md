# Release Readiness & Residual Risk Register — SPEC-EI-036

Full release readiness checklist, final coverage audit results, the two genuine
gaps found and fixed during this phase (missing Canary-lifecycle REST endpoints;
no application-layer path to roll back a `PROMOTED` candidate), and the honest
residual-risk register (R1–R7: no real RabbitMQ AMQP consumer/publisher yet,
`memory.retrieval.completed.v1` has no real upstream producer yet, LLM Judge is
quality-only by design, 08-observability-platform has no real backend to
integrate with yet, 06's cross-service identity mechanism doesn't exist yet,
LangSmith SDK mode is an explicit opt-in) — see `release-readiness_CN.md` for
the full text.
