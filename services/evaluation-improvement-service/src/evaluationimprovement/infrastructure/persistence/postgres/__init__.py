"""Real `evaluation.*` PostgreSQL schema + SQLAlchemy models/repositories are
SPEC-EI-002 (evaluation-schema-baseline) scope, mirroring SPEC-MK-001/SPEC-MK-002's
own split in the sibling memory-knowledge-service. SPEC-EI-001 ships only the
in-memory adapters (infrastructure.persistence.in_memory) behind the same
application.ports_out Protocols, so this package intentionally stays empty until
SPEC-EI-002 lands.
"""
