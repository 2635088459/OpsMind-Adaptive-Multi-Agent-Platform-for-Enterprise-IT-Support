"""SPEC-ARO-001: entry point for the Agent Runtime Orchestration service. Runtime
owns only Agent Workflow state (see agentruntime.domain) and never mutates Ticket
state directly; it observes Ticket state only through
agentruntime.application.ports_out.TicketSnapshotPort.
"""

from __future__ import annotations

import logging

from fastapi import FastAPI

from agentruntime.infrastructure.observability import configure_observability
from agentruntime.interfaces.admin.router import router as admin_router
from agentruntime.interfaces.conversation.router import router as conversation_router
from agentruntime.interfaces.errors import register_exception_handlers
from agentruntime.interfaces.event.router import router as event_router
from agentruntime.interfaces.rest.router import router as workflow_router
from agentruntime.interfaces.worker.router import router as worker_router
from agentruntime.settings import get_settings


def _configure_logging() -> None:
    """SPEC-ARO-026 12-observability §"日志": every application-service logger.info()
    call (the structured "agent task event published" lines, and every module's own
    logger.error()) is otherwise silently dropped — Python's root logger defaults to
    WARNING, and nothing before this spec ever raised it. Root stays at WARNING (third-
    party library chatter — sqlalchemy, pika — is not this service's own observability
    surface); only the `agentruntime` namespace itself is raised to INFO. Idempotent: a
    second call (e.g. TestClient(create_app()) constructed repeatedly across a test
    session) is harmless.
    """
    logging.basicConfig(level=logging.WARNING, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    logging.getLogger("agentruntime").setLevel(logging.INFO)


def create_app() -> FastAPI:
    _configure_logging()
    configure_observability(get_settings())
    app = FastAPI(title="agent-runtime-service", version="0.1.0")

    register_exception_handlers(app)

    app.include_router(workflow_router)
    app.include_router(worker_router)
    app.include_router(event_router)
    app.include_router(admin_router)
    app.include_router(conversation_router)

    @app.get("/health", tags=["health"])
    def health() -> dict[str, str]:
        return {"status": "UP"}

    return app


app = create_app()


def run() -> None:
    import uvicorn

    uvicorn.run("agentruntime.main:app", host="0.0.0.0", port=8000, reload=False)


if __name__ == "__main__":
    run()
