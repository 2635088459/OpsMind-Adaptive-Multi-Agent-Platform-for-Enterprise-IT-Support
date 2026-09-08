"""ConsumeImprovementService — the agent-runtime side of the platform's
"improvement is evaluation-gated" loop.

evaluation-improvement-service publishes `improvement.promoted.v1` once a
candidate has passed benchmark + release gate + approval + canary; this service
adopts it by writing the new active version of its `target_component` to
`active_component_configs`, superseding whatever was there. A matching
`improvement.rollback.requested.v1` clears the row, reverting that component to
its built-in default.

Every promoted config is stored and visible on the admin surface. Only one
component is actually *read* back into behaviour today —
`conversation_reasoning_prompt` (see container._build_conversation_reasoning_port
and the reasoning adapters' `system_prompt_provider`); wiring the other
CandidateType targets (routing, tool-schema hint, retrieval config,
verification checklist) is a per-component follow-up, not a silent gap.
"""

from __future__ import annotations

from opentelemetry import trace

from agentruntime.application.commands import (
    ConsumeImprovementPromotedCommand,
    ConsumeImprovementRollbackCommand,
)
from agentruntime.application.ports_out import (
    ActiveComponentConfigRepository,
    ClockPort,
    ProcessedEventRepository,
)
from agentruntime.application.records import ActiveComponentConfig
from agentruntime.application.telemetry import RuntimeTelemetry

tracer = trace.get_tracer(__name__)

CONSUMER_NAME = "consume_improvement"


class ConsumeImprovementService:
    def __init__(
        self,
        processed_event_repository: ProcessedEventRepository,
        active_component_config_repository: ActiveComponentConfigRepository,
        clock: ClockPort,
        telemetry: RuntimeTelemetry,
    ) -> None:
        self._processed_event_repository = processed_event_repository
        self._config_repository = active_component_config_repository
        self._clock = clock
        self._telemetry = telemetry

    def consume_promoted(self, command: ConsumeImprovementPromotedCommand) -> bool:
        with tracer.start_as_current_span("event.consumed"):
            if self._processed_event_repository.is_processed(command.event_id, CONSUMER_NAME):
                self._telemetry.record_event_duplicate(command.event_type)
                return False
            now = self._clock.now()
            self._config_repository.upsert(ActiveComponentConfig(
                component=command.target_component,
                version=command.promoted_version,
                payload=dict(command.proposed_change),
                source_candidate_id=command.candidate_id,
                activated_at=now,
            ))
            self._processed_event_repository.mark_processed(
                command.event_id, CONSUMER_NAME, now, event_type=command.event_type,
            )
            return True

    def consume_rollback(self, command: ConsumeImprovementRollbackCommand) -> bool:
        with tracer.start_as_current_span("event.consumed"):
            if self._processed_event_repository.is_processed(command.event_id, CONSUMER_NAME):
                self._telemetry.record_event_duplicate(command.event_type)
                return False
            now = self._clock.now()
            # The rollback event names the candidate, not the component — remove
            # whichever active row this candidate promoted (there is at most one).
            for config in self._config_repository.find_all():
                if config.source_candidate_id == command.candidate_id:
                    self._config_repository.clear(config.component)
            self._processed_event_repository.mark_processed(
                command.event_id, CONSUMER_NAME, now, event_type=command.event_type,
            )
            return True
