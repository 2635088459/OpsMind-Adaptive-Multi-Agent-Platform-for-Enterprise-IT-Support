"""SPEC-ARO-001: entry point for the Agent Runtime Orchestration service. Runtime
owns only Agent Workflow state (see agentruntime.domain) and never mutates Ticket
state directly; it observes Ticket state only through
agentruntime.application.ports_out.TicketSnapshotPort.
"""

from __future__ import annotations

from fastapi import FastAPI

from agentruntime.interfaces.admin.router import router as admin_router
from agentruntime.interfaces.errors import register_exception_handlers
from agentruntime.interfaces.event.router import router as event_router
from agentruntime.interfaces.rest.router import router as workflow_router
from agentruntime.interfaces.worker.router import router as worker_router


def create_app() -> FastAPI:
    app = FastAPI(title="agent-runtime-service", version="0.1.0")

    register_exception_handlers(app)

    app.include_router(workflow_router)
    app.include_router(worker_router)
    app.include_router(event_router)
    app.include_router(admin_router)

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
