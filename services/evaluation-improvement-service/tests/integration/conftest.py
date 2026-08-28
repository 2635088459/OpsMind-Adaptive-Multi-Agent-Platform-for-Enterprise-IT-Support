"""SPEC-EI-002 acceptance-criteria: integration tests cover PostgreSQL migration,
unique keys, indexes, and JSONB fields. Spins up a real, ephemeral Postgres via
testcontainers (`postgres:18`, the same major version
`infrastructure/docker-compose/local-platform.yml` runs), runs the real Alembic
migration against it, and hands each test a fresh SQLAlchemy session factory.
Mirrors memory-knowledge-service's own tests/integration/conftest.py exactly.
"""

from __future__ import annotations

from collections.abc import Iterator
from pathlib import Path
from urllib.parse import urlparse

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy.orm import Session, sessionmaker
from testcontainers.community.postgres import PostgresContainer

from evaluationimprovement.infrastructure.persistence.postgres.models import Base
from evaluationimprovement.infrastructure.persistence.postgres.session import build_engine, build_session_factory
from evaluationimprovement.settings import Settings

_REPO_ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture(scope="session")
def postgres_url() -> Iterator[str]:
    with PostgresContainer("postgres:18", driver="psycopg") as postgres:
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
    """Truncates every evaluation table before each test so tests stay
    order-independent without needing a container restart per test.
    """
    yield
    with migrated_engine.begin() as connection:
        for table in reversed(Base.metadata.sorted_tables):
            connection.execute(table.delete())


@pytest.fixture
def postgres_settings(postgres_url: str) -> Settings:
    """A real Settings object pointed at the ephemeral container — never derived from
    get_settings()/env vars, since testcontainers assigns a random host port per
    session.
    """
    parsed = urlparse(postgres_url.replace("postgresql+psycopg", "postgresql"))
    return Settings(
        evaluation_persistence="postgres", db_host=parsed.hostname or "localhost", db_port=parsed.port or 5432,
        db_name=(parsed.path or "/test").lstrip("/"), db_username=parsed.username or "test", db_password=parsed.password or "test",
    )
