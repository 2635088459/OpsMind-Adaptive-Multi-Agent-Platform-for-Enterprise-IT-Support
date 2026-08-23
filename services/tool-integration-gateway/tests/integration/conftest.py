"""SPEC-TG-002 acceptance-criteria: integration tests cover persistence,
outbox, and processed-event dedup against a real, ephemeral Postgres via
testcontainers — runs the real Alembic migration against it (the same DDL a
real deployment applies, not a shortcut like ``Base.metadata.create_all``), and
hands each test a fresh SQLAlchemy session factory. Mirrors memory-knowledge-
service's own tests/integration/conftest.py exactly.
"""

from __future__ import annotations

from collections.abc import Iterator
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy.orm import Session, sessionmaker
from testcontainers.community.postgres import PostgresContainer

from tool_gateway.adapters.db.models import Base
from tool_gateway.adapters.db.session import build_engine, build_session_factory

_REPO_ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture(scope="session")
def postgres_url() -> Iterator[str]:
    with PostgresContainer("postgres:18", driver="psycopg") as postgres:
        yield postgres.get_connection_url()


@pytest.fixture(scope="session")
def migrated_engine(postgres_url: str):
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
    """Truncates every tool table before each test so tests stay
    order-independent without needing a container restart per test.
    """

    yield
    with migrated_engine.begin() as connection:
        for table in reversed(Base.metadata.sorted_tables):
            connection.execute(table.delete())
