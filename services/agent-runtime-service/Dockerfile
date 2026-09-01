# syntax=docker/dockerfile:1
#
# New for project-level integration verification (2026-09-01): this is the
# first real Dockerfile for this service. Mirrors the sibling Java services'
# own multi-stage pattern (build stage compiles/installs, runtime stage is
# slim + a non-root user), adapted for uv-managed Python.
FROM python:3.13-slim AS build
WORKDIR /app

RUN pip install --no-cache-dir uv

COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-install-project --no-dev

COPY src/ src/
COPY migrations/ migrations/
COPY alembic.ini ./
RUN uv sync --frozen --no-dev

FROM python:3.13-slim AS runtime

RUN groupadd --system opsmind \
    && useradd --system --gid opsmind --home-dir /app --no-create-home opsmind

WORKDIR /app
COPY --from=build /app /app

ENV PATH="/app/.venv/bin:$PATH"
USER opsmind
EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health', timeout=3)" || exit 1

# Migrations run against the shared Postgres before the app starts -- this
# service's own dedicated schema (see migrations/env.py), safe to run
# alongside the other services' own migrations against the same database.
ENTRYPOINT ["sh", "-c", "alembic upgrade head && exec python -m agentruntime.main"]
