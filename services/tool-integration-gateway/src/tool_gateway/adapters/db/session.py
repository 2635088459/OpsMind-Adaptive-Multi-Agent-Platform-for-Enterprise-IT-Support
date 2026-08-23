"""SPEC-TG-002: engine/session construction. Not literally named by
13-package-and-class-design's own adapters/db/ tree (which lists only
``repositories.py``/``models.py``/``migrations/``) — added the same necessary
extension memory-knowledge-service's own SPEC-MK-002
infrastructure/persistence/postgres/session.py made. ``create_engine`` does not
open a connection — the pool connects lazily on first use — so constructing a
session factory here is safe even in processes (like the unit test suite) that
never touch Postgres.
"""

from __future__ import annotations

from sqlalchemy import Engine, create_engine
from sqlalchemy.orm import Session, sessionmaker


def build_engine(sqlalchemy_url: str) -> Engine:
    return create_engine(sqlalchemy_url, pool_pre_ping=True)


def build_session_factory(engine: Engine) -> sessionmaker[Session]:
    return sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)
