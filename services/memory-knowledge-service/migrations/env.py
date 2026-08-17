import sys
from logging.config import fileConfig
from pathlib import Path

from sqlalchemy import engine_from_config, pool, text

from alembic import context

# `src/` layout: migrations/ sits next to src/, not under it.
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from memoryknowledge.infrastructure.persistence.postgres.models import Base  # noqa: E402
from memoryknowledge.settings import get_settings  # noqa: E402

config = context.config

if config.config_file_name is not None:
    fileConfig(config.config_file_name)

# 07-data-model §"Schema": all memory tables live under one dedicated schema —
# target_metadata drives `alembic revision --autogenerate` for future migrations;
# SPEC-MK-002's initial migration is hand-written to match 07-data-model precisely
# (see migrations/versions/*_create_memory_schema.py).
target_metadata = Base.metadata

# The DB URL comes from memoryknowledge.settings (DB_HOST/DB_PORT/DB_NAME/
# DB_USERNAME/DB_PASSWORD env vars) by default — same convention as the sibling
# ticket-workflow-service's Flyway config and agent-runtime-service's own Alembic
# config — never a real URL committed to alembic.ini (it only holds a dummy
# placeholder). Tests that invoke `alembic.command.upgrade()` programmatically
# against an ephemeral testcontainers database set a real "postgresql..." URL on
# the Config object *before* calling upgrade(); that already-configured value must
# win over the env-var default, or every programmatic run would silently migrate
# the wrong (dev) database instead of the one under test.
_configured_url = config.get_main_option("sqlalchemy.url")
if not _configured_url or not _configured_url.startswith("postgresql"):
    config.set_main_option("sqlalchemy.url", get_settings().sqlalchemy_url)


def include_object(object, name, type_, reflected, compare_to):
    if type_ == "schema":
        return name == Base.metadata.schema
    return True


# SPEC-MK-032 final coverage audit: the shared Postgres database is also
# agent-runtime-service's own — its `migrations/env.py` never sets
# `version_table_schema` either, so without one here both services' alembic
# histories would collide in the very same default `public.alembic_version` table
# (confirmed by inspecting the shared database: it already tracks
# agent-runtime-service's own head revision, a revision id this repo's own
# migration chain doesn't contain at all). Scoping this service's own version
# table to its own dedicated `memory` schema — the same isolation
# 07-data-model §"Schema" already requires of every real table — closes that
# collision. `CREATE SCHEMA IF NOT EXISTS` must run before context.configure()
# itself: alembic creates `<schema>.alembic_version` on its own, but never the
# schema that's meant to contain it.
_VERSION_TABLE_SCHEMA = Base.metadata.schema


def run_migrations_offline() -> None:
    url = config.get_main_option("sqlalchemy.url")
    context.configure(
        url=url,
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        include_schemas=True,
        include_object=include_object,
        version_table_schema=_VERSION_TABLE_SCHEMA,
    )

    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    connectable = engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )

    with connectable.connect() as connection:
        connection.execute(text(f"CREATE SCHEMA IF NOT EXISTS {_VERSION_TABLE_SCHEMA}"))
        connection.commit()
        context.configure(
            connection=connection,
            target_metadata=target_metadata,
            include_schemas=True,
            include_object=include_object,
            version_table_schema=_VERSION_TABLE_SCHEMA,
        )

        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
