from __future__ import annotations

import uuid
from datetime import timedelta

import pytest

from agentruntime.application.records import AuditRecordEntry
from agentruntime.application.services.audit_query import AuditRecordQueryService
from agentruntime.infrastructure.persistence.in_memory import InMemoryAuditRecordRepository
from tests.support.clock import FakeClock

pytestmark = pytest.mark.unit


def _entry(clock: FakeClock, **overrides) -> AuditRecordEntry:
    defaults = dict(
        id=uuid.uuid4(), audit_type="WORKFLOW_TRANSITION", action="start_workflow", resource_type="WorkflowInstance",
        resource_id=str(uuid.uuid4()), workflow_instance_id=None, ticket_id=None, actor_type="SYSTEM", actor_id=None,
        outcome="SUCCESS", correlation_id=None, causation_id=None, detail="{}", occurred_at=clock.now(),
    )
    defaults.update(overrides)
    return AuditRecordEntry(**defaults)


def test_list_audit_events_returns_newest_first() -> None:
    repository = InMemoryAuditRecordRepository()
    clock = FakeClock()
    repository.append(_entry(clock, action="older"))
    clock.advance(timedelta(seconds=10))
    repository.append(_entry(clock, action="newer"))
    service = AuditRecordQueryService(repository)

    entries = service.list_audit_events(10)

    assert [entry.action for entry in entries] == ["newer", "older"]


def test_list_audit_events_respects_the_limit() -> None:
    repository = InMemoryAuditRecordRepository()
    clock = FakeClock()
    for i in range(3):
        repository.append(_entry(clock, action=f"action-{i}"))
    service = AuditRecordQueryService(repository)

    entries = service.list_audit_events(2)

    assert len(entries) == 2
