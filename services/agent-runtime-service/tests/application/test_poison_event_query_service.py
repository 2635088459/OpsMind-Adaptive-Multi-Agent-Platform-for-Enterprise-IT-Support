from __future__ import annotations

import uuid
from datetime import timedelta

import pytest

from agentruntime.application.exceptions import PoisonEventNotFoundException
from agentruntime.application.records import PoisonEventRecord
from agentruntime.application.services.poison_event_query import PoisonEventQueryService
from agentruntime.infrastructure.persistence.in_memory import InMemoryPoisonEventRepository
from tests.support.clock import FakeClock

pytestmark = pytest.mark.unit


@pytest.fixture
def wiring():
    poison_event_repository = InMemoryPoisonEventRepository()
    clock = FakeClock()
    service = PoisonEventQueryService(poison_event_repository, clock)
    return service, poison_event_repository, clock


def _poison_event_record(clock: FakeClock, **overrides) -> PoisonEventRecord:
    now = clock.now()
    defaults = dict(
        id=uuid.uuid4(), event_id="evt-1", consumer_name="tool_result_consumer", event_type="tool.completed.v1",
        payload="{bad json", error_message="could not parse payload", occurred_at=now, recorded_at=now,
    )
    defaults.update(overrides)
    return PoisonEventRecord(**defaults)


def test_list_poison_events_returns_newest_first(wiring) -> None:
    service, poison_event_repository, clock = wiring
    poison_event_repository.record(_poison_event_record(clock, event_id="evt-older"))
    clock.advance(timedelta(seconds=10))
    poison_event_repository.record(_poison_event_record(clock, event_id="evt-newer"))

    views = service.list_poison_events(10)

    assert [view.event_id for view in views] == ["evt-newer", "evt-older"]
    assert all(view.quarantined_at is None for view in views)


def test_mark_quarantined_sets_the_timestamp(wiring) -> None:
    service, poison_event_repository, clock = wiring
    record = poison_event_repository.record(_poison_event_record(clock))

    view = service.mark_quarantined(record.id)

    assert view.quarantined_at == clock.now()
    [listed] = service.list_poison_events(10)
    assert listed.quarantined_at == clock.now()


def test_mark_quarantined_an_unknown_id_is_rejected(wiring) -> None:
    service = wiring[0]

    with pytest.raises(PoisonEventNotFoundException):
        service.mark_quarantined(uuid.uuid4())
