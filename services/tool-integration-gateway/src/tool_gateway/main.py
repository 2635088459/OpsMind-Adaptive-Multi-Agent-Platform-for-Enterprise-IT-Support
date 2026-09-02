"""SPEC-TG-001: entry point for the Tool Integration Gateway service.
INV-TG-001: Tool Gateway is the only tool execution entry point — this app
exposes the Runtime API, connector admin API, and result API; nothing outside
this service ever calls a connector directly.
"""

from __future__ import annotations

import logging

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from tool_gateway.adapters.observability.otel_setup import configure_observability
from tool_gateway.api.admin_routes import router as admin_router
from tool_gateway.api.connector_admin_routes import router as connector_admin_router
from tool_gateway.api.errors import register_exception_handlers
from tool_gateway.api.event_routes import router as event_router
from tool_gateway.api.result_routes import router as result_router
from tool_gateway.api.runtime_routes import router as runtime_router
from tool_gateway.settings import get_settings


def _configure_logging() -> None:
    """Root stays at WARNING; only the ``tool_gateway`` namespace is raised to
    INFO. Idempotent — mirrors memory-knowledge-service's own
    ``main._configure_logging``.
    """

    logging.basicConfig(level=logging.WARNING, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    logging.getLogger("tool_gateway").setLevel(logging.INFO)


def create_app() -> FastAPI:
    _configure_logging()
    settings = get_settings()
    configure_observability(settings)
    app = FastAPI(title="tool-integration-gateway", version="0.1.0")

    # SPEC-SC-018/020 follow-up: real, empty/deny-by-default CORS — see
    # Settings.cors_allowed_origins's own docstring for why GET-only and why
    # X-Caller-Id/X-Caller-Type are deliberately excluded from allow_headers.
    if settings.cors_allowed_origins_list:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=settings.cors_allowed_origins_list,
            allow_methods=["GET", "OPTIONS"],
            allow_headers=["Authorization", "Content-Type", "traceparent"],
            allow_credentials=False,
        )

    register_exception_handlers(app)

    app.include_router(runtime_router)
    app.include_router(connector_admin_router)
    app.include_router(result_router)
    app.include_router(event_router)
    app.include_router(admin_router)

    @app.get("/health", tags=["health"])
    def health() -> dict[str, str]:
        return {"status": "UP"}

    return app


app = create_app()


def run() -> None:
    import uvicorn

    uvicorn.run("tool_gateway.main:app", host="0.0.0.0", port=8020, reload=False)


if __name__ == "__main__":
    run()
