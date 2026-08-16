from __future__ import annotations

from datetime import UTC, datetime

import pytest

from memoryknowledge.domain.enums import MemoryType
from memoryknowledge.domain.exceptions import InvalidMemoryVersionTransitionException, MemoryVersionMissingSourceRefException
from memoryknowledge.domain.ids import MemoryId, MemoryVersionId
from memoryknowledge.domain.memory import Memory, MemoryVersion
from memoryknowledge.domain.values import RedactionReport, SourceRef

pytestmark = pytest.mark.unit


def _now() -> datetime:
    return datetime.now(UTC)


def _active_version() -> MemoryVersion:
    return MemoryVersion.create_active(
        MemoryVersionId.new_id(), MemoryId.new_id(), 1, "content", "summary", (SourceRef("ticket", "T-1"),),
        RedactionReport(), 0.8, 0.7, "hash-1", "agent-1", _now(),
    )


def test_create_active_requires_at_least_one_source_ref() -> None:
    with pytest.raises(MemoryVersionMissingSourceRefException):
        MemoryVersion.create_active(
            MemoryVersionId.new_id(), MemoryId.new_id(), 1, "content", "summary", (), RedactionReport(), 0.8, 0.7, "hash", "agent-1", _now(),
        )


def test_memory_create_is_a_pure_identity() -> None:
    memory = Memory.create(MemoryId.new_id(), MemoryType.EPISODIC, _now())
    assert memory.memory_type is MemoryType.EPISODIC


def test_supersede_deprecate_delete_transitions() -> None:
    version = _active_version()

    superseded = version.supersede()
    assert superseded.status.name == "SUPERSEDED"

    deleted = superseded.delete()
    assert deleted.status.name == "DELETED"


def test_deprecate_requires_active_status() -> None:
    version = _active_version().supersede()
    with pytest.raises(InvalidMemoryVersionTransitionException):
        version.deprecate()


def test_delete_from_deleted_is_rejected() -> None:
    deleted = _active_version().delete()
    with pytest.raises(InvalidMemoryVersionTransitionException):
        deleted.delete()
