"""SPEC-MK-002 acceptance-criteria: "Integration tests cover persistence, outbox, and
processed-event." Spins up a real, ephemeral Postgres via testcontainers — using
`pgvector/pgvector:pg18` (the same image infrastructure/docker-compose/
local-platform.yml now uses: postgres:18 plus the pgvector extension pre-installed,
needed for `memory.embeddings`) — runs the real Alembic migration against it, and
hands each test a fresh SQLAlchemy session factory. Mirrors agent-runtime-service's
own tests/integration/conftest.py exactly.
"""

from __future__ import annotations

from collections.abc import Iterator
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy.orm import Session, sessionmaker
from testcontainers.community.postgres import PostgresContainer

from memoryknowledge.infrastructure.persistence.postgres.models import Base
from memoryknowledge.infrastructure.persistence.postgres.session import build_engine, build_session_factory

_REPO_ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture(scope="session")
def postgres_url() -> Iterator[str]:
    with PostgresContainer("pgvector/pgvector:pg18", driver="psycopg") as postgres:
        yield postgres.get_connection_url()


@pytest.fixture(scope="session")
def migrated_engine(postgres_url: str):
    """Runs the real migrations/versions/*.py against the container — this is the
    same DDL a real deployment applies, not a shortcut like `Base.metadata.create_all`.
    """
    alembic_cfg = Config(str(_REPO_ROOT / "alembic.ini"))
    alembic_cfg.set_main_option("script_location", str(_REPO_ROOT / "migrations"))
    alembic_cfg.set_main_option("sqlalchemy.url", postgres_url)
    command.upgrade(alembic_cfg, "head")

    engine = build_engine(postgres_url)
    yield engine
    engine.dispose()


@pytest.fixture
def session_factory(migrated_engine) -> sessionmaker[Session]:
    return build_session_factory(migrated_engine)


@pytest.fixture(autouse=True)
def _clean_tables(migrated_engine):
    """Truncates every memory table before each test so tests stay order-independent
    without needing a container restart per test.
    """
    yield
    with migrated_engine.begin() as connection:
        for table in reversed(Base.metadata.sorted_tables):
            connection.execute(table.delete())
