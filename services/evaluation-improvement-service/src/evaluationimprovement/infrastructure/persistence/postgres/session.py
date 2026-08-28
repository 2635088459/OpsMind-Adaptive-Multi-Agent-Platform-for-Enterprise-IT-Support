"""Engine/session construction. `create_engine` does not open a connection — the
pool connects lazily on first use — so constructing a session factory here is safe
even in processes (like the test suite) that never touch Postgres.
"""

from __future__ import annotations

from sqlalchemy import Engine, create_engine
from sqlalchemy.orm import Session, sessionmaker


def build_engine(sqlalchemy_url: str) -> Engine:
    return create_engine(sqlalchemy_url, pool_pre_ping=True)


def build_session_factory(engine: Engine) -> sessionmaker[Session]:
    return sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)
