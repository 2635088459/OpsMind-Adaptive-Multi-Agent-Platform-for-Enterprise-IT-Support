"""SPEC-EI-001: entry point for the Evaluation Improvement service. This domain owns
only evaluation facts, gate decisions, candidate proposals, and rollback
recommendations (see evaluationimprovement.domain) and never mutates Agent, Prompt,
Policy, Tool, Ticket, Workflow, or Memory state directly — see domain-rules "forbidden"
list and tests/architecture/test_no_production_mutation.py.
"""

from __future__ import annotations

import logging

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from evaluationimprovement.infrastructure.observability import configure_observability
from evaluationimprovement.interfaces.admin.router import router as admin_router
from evaluationimprovement.interfaces.errors import register_exception_handlers
from evaluationimprovement.interfaces.event.router import router as event_router
from evaluationimprovement.interfaces.rest.router import router as evaluation_router
from evaluationimprovement.settings import get_settings


def _configure_logging() -> None:
    """Root stays at WARNING; only the `evaluationimprovement` namespace is raised to
    INFO. Idempotent — a second call is harmless.
    """
    logging.basicConfig(level=logging.WARNING, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    logging.getLogger("evaluationimprovement").setLevel(logging.INFO)


def create_app() -> FastAPI:
    _configure_logging()
    settings = get_settings()
    configure_observability(settings)
    app = FastAPI(title="evaluation-improvement-service", version="0.1.0")

    # SPEC-SC-015: support-console's own Evaluation Comparison Table is the first
    # browser caller of this service — empty/deny by default, an operator opts
    # specific frontend origins in (settings.cors_allowed_origins).
    if settings.cors_allowed_origins_list:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=settings.cors_allowed_origins_list,
            allow_methods=["GET", "POST", "OPTIONS"],
            allow_headers=["Authorization", "Content-Type", "traceparent"],
            allow_credentials=False,
        )

    register_exception_handlers(app)

    app.include_router(evaluation_router)
    app.include_router(admin_router)
    app.include_router(event_router)

    @app.get("/health", tags=["health"])
    def health() -> dict[str, str]:
        return {"status": "UP"}

    return app


app = create_app()


def run() -> None:
    import uvicorn

    uvicorn.run("evaluationimprovement.main:app", host="0.0.0.0", port=8011, reload=False)


if __name__ == "__main__":
    run()
