"""13-package-and-class-design §"Application Layer": ConsumeWorkflowMemorySourceEventService,
the sole implementation of WorkflowMemorySourceEventConsumerPort. SPEC-MK-022
06-event-contracts: consumes `workflow.completed.v1` and `workflow.failed.v1`
(03-agent-runtime-orchestration's own real published events) and triggers candidate
extraction via ExtractMemoryCandidateUseCase — the same shape SPEC-MK-010's own
ConsumeTicketMemorySourceEventService established for 02's own events.

Both memory_type EPISODIC, never PROCEDURAL: 06-event-contracts' own note on
workflow.failed.v1 ("记录失败经验，但默认不自动发布为 procedural memory") is naturally
satisfied by this codebase's existing pipeline — nothing anywhere auto-approves an
EXTRACTED candidate into a published Memory, procedural or otherwise, so the real
requirement here is narrower: don't even *label* a bare failure signal as reusable
procedural knowledge before a human validates it, which starting from EPISODIC
(01-domain-model: "一次具体 ticket 的症状、证据、根因、动作、结果") already ensures.

10-failure-handling §"Poison Event": "不标记 processed，除非明确 quarantine" — same
divergence from agent-runtime-service's own unconditional `finally: mark_processed`
precedent that SPEC-MK-010's own consumer already documents and applies.
"""

from __future__ import annotations

from memoryknowledge.application.commands import (
    ConsumeWorkflowCompletedCommand,
    ConsumeWorkflowFailedCommand,
    ExtractMemoryCandidateCommand,
)
from memoryknowledge.application.ports_in import ExtractMemoryCandidateUseCase
from memoryknowledge.application.ports_out import ClockPort, ProcessedEventRepository
from memoryknowledge.domain.enums import MemoryType
from memoryknowledge.domain.ids import IdempotencyKey
from memoryknowledge.domain.values import SourceRef

# SPEC-MK-022 09-concurrency-and-idempotency §"消费事件幂等": this service's own
# identity in the (event_id, consumer_name) dedup key.
CONSUMER_NAME = "consume_workflow_memory_source_event"


class ConsumeWorkflowMemorySourceEventService:
    def __init__(
        self, processed_event_repository: ProcessedEventRepository, extract_memory_candidate_port: ExtractMemoryCandidateUseCase,
        clock: ClockPort,
    ) -> None:
        self._processed_event_repository = processed_event_repository
        self._extract_memory_candidate_port = extract_memory_candidate_port
        self._clock = clock

    def consume_completed(self, command: ConsumeWorkflowCompletedCommand) -> bool:
        if self._processed_event_repository.is_processed(command.event_id, CONSUMER_NAME):
            return False

        candidate_text = (
            f"Workflow {command.workflow_instance_id} completed "
            f"({command.from_state or 'UNKNOWN'} -> {command.to_state}, version {command.workflow_version})"
        )
        self._extract_memory_candidate_port.extract(ExtractMemoryCandidateCommand(
            memory_type=MemoryType.EPISODIC,
            source_refs=(SourceRef(source_type="workflow", source_id=str(command.workflow_instance_id), field_path="completion"),),
            candidate_text=candidate_text,
            idempotency_key=IdempotencyKey(f"workflow-completed:{command.workflow_instance_id}:{command.workflow_version}"),
            extracted_by="workflow-completed-consumer",
        ))
        self._processed_event_repository.mark_processed(command.event_id, CONSUMER_NAME, self._clock.now(), "workflow.completed.v1")
        return True

    def consume_failed(self, command: ConsumeWorkflowFailedCommand) -> bool:
        if self._processed_event_repository.is_processed(command.event_id, CONSUMER_NAME):
            return False

        candidate_text = (
            f"Workflow {command.workflow_instance_id} failed "
            f"({command.from_state or 'UNKNOWN'} -> {command.to_state}, version {command.workflow_version}): {command.failure_reason}"
        )
        self._extract_memory_candidate_port.extract(ExtractMemoryCandidateCommand(
            memory_type=MemoryType.EPISODIC,
            source_refs=(SourceRef(source_type="workflow", source_id=str(command.workflow_instance_id), field_path="failure"),),
            candidate_text=candidate_text,
            idempotency_key=IdempotencyKey(f"workflow-failed:{command.workflow_instance_id}:{command.workflow_version}"),
            extracted_by="workflow-failed-consumer",
        ))
        self._processed_event_repository.mark_processed(command.event_id, CONSUMER_NAME, self._clock.now(), "workflow.failed.v1")
        return True
