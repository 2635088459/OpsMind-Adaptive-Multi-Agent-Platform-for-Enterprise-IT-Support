"""ConsumeImprovementService — the agent-runtime side of the "improvement is
evaluation-gated" loop. A promoted improvement is stored as the active version
of its component; a rollback removes it. Idempotent per event_id.
"""

from __future__ import annotations

import pytest

from agentruntime.application.commands import (
    ConsumeImprovementPromotedCommand,
    ConsumeImprovementRollbackCommand,
)
from agentruntime.application.services.consume_improvement import CONSUMER_NAME, ConsumeImprovementService
from agentruntime.infrastructure.persistence.in_memory import (
    InMemoryActiveComponentConfigRepository,
    InMemoryProcessedEventRepository,
)
from tests.support.clock import FakeClock
from tests.support.telemetry import build_telemetry_collaborators

pytestmark = pytest.mark.unit


def _service():
    clock = FakeClock()
    telemetry, _ = build_telemetry_collaborators(clock)
    processed = InMemoryProcessedEventRepository()
    configs = InMemoryActiveComponentConfigRepository()
    return ConsumeImprovementService(processed, configs, clock, telemetry), processed, configs


def _promoted(event_id="evt-1", candidate_id="cand-1", component="conversation_reasoning_prompt", change=None):
    return ConsumeImprovementPromotedCommand(
        event_id=event_id, event_type="improvement.promoted.v1", producer="evaluation-improvement-service",
        occurred_at=FakeClock().now(), candidate_id=candidate_id, candidate_type="PROMPT_CHANGE",
        target_component=component, promoted_version="v2",
        proposed_change=change or {"system_prompt": "You are a terse IT assistant."},
    )


def test_promote_stores_the_active_component_config() -> None:
    service, _, configs = _service()

    applied = service.consume_promoted(_promoted())

    assert applied is True
    stored = configs.find("conversation_reasoning_prompt")
    assert stored is not None
    assert stored.version == "v2"
    assert stored.source_candidate_id == "cand-1"
    assert stored.payload == {"system_prompt": "You are a terse IT assistant."}


def test_promote_is_idempotent_per_event_id() -> None:
    service, processed, configs = _service()
    service.consume_promoted(_promoted())

    second = service.consume_promoted(_promoted())  # same event_id

    assert second is False
    assert processed.is_processed("evt-1", CONSUMER_NAME)


def test_a_second_promotion_replaces_the_component_version() -> None:
    service, _, configs = _service()
    service.consume_promoted(_promoted(event_id="evt-1"))
    service.consume_promoted(_promoted(event_id="evt-2", change={"system_prompt": "newer prompt"}))

    stored = configs.find("conversation_reasoning_prompt")
    assert stored.payload == {"system_prompt": "newer prompt"}


def test_rollback_clears_the_component_the_candidate_promoted() -> None:
    service, _, configs = _service()
    service.consume_promoted(_promoted(event_id="evt-1", candidate_id="cand-1"))

    applied = service.consume_rollback(ConsumeImprovementRollbackCommand(
        event_id="evt-rb", event_type="improvement.rollback.requested.v1",
        producer="evaluation-improvement-service", occurred_at=FakeClock().now(),
        candidate_id="cand-1", reason="production incident",
    ))

    assert applied is True
    assert configs.find("conversation_reasoning_prompt") is None


def test_rollback_for_an_unknown_candidate_is_a_harmless_noop() -> None:
    service, _, configs = _service()
    service.consume_promoted(_promoted(candidate_id="cand-1"))

    service.consume_rollback(ConsumeImprovementRollbackCommand(
        event_id="evt-rb", event_type="improvement.rollback.requested.v1",
        producer="evaluation-improvement-service", occurred_at=FakeClock().now(),
        candidate_id="some-other-candidate", reason="unrelated",
    ))

    assert configs.find("conversation_reasoning_prompt") is not None
