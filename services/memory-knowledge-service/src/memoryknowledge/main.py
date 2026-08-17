"""SPEC-MK-001: entry point for the Memory Knowledge service. Memory Knowledge owns
only memory/knowledge/retrieval/provenance state (see memoryknowledge.domain) and never
mutates Ticket or Workflow state directly; it observes those only through
memoryknowledge.application.ports_out.TicketSnapshotPort / WorkflowTracePort.
"""

from __future__ import annotations

import logging

from fastapi import FastAPI

from memoryknowledge.interfaces.admin.router import router as admin_router
from memoryknowledge.interfaces.errors import register_exception_handlers
from memoryknowledge.interfaces.event.router import router as event_router
from memoryknowledge.interfaces.rest.router import router as memory_router


def _configure_logging() -> None:
    """Root stays at WARNING (third-party library chatter is not this service's own
    observability surface); only the `memoryknowledge` namespace is raised to INFO.
    Idempotent — a second call (e.g. TestClient(create_app()) constructed repeatedly
    across a test session) is harmless. Mirrors agent-runtime-service's own
    main._configure_logging.
    """
    logging.basicConfig(level=logging.WARNING, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    logging.getLogger("memoryknowledge").setLevel(logging.INFO)


def create_app() -> FastAPI:
    _configure_logging()
    app = FastAPI(title="memory-knowledge-service", version="0.1.0")

    register_exception_handlers(app)

    app.include_router(memory_router)
    app.include_router(admin_router)
    app.include_router(event_router)

    @app.get("/health", tags=["health"])
    def health() -> dict[str, str]:
        return {"status": "UP"}

    return app


app = create_app()


def run() -> None:
    import uvicorn

    uvicorn.run("memoryknowledge.main:app", host="0.0.0.0", port=8010, reload=False)


if __name__ == "__main__":
    run()
