"""SPEC-EI-030 (ticket-runtime-evaluation-contract) / SPEC-EI-031 (memory-tool-
evidence-contract): ConsumeCrossDomainEventService, the sole implementation of
CrossDomainEventConsumerPort. 04-use-cases UC-EI-006 step 1: "07 消费 workflow
completed、ticket reopened、tool failed、approval denied 等事件" — this is that
consumption boundary, closing the gap CollectOnlineSampleCommand's own docstring
(SPEC-EI-028) named: which upstream event triggers a sample, and the redaction of it,
both land here.

09-concurrency-and-idempotency §"消费事件幂等": every method dedups on (event_id,
CONSUMER_NAME) via ProcessedEventRepository before doing any work, mirroring
memory-knowledge-service's own ConsumeTicketMemorySourceEventService precedent
exactly — one shared consumer identity for every event type this service handles,
since they all fund the same downstream action (an online sample).

11-security §"数据保护": every event carries some free-text or potentially-raw field
(a ticket resolution summary, a tool result's structured_output, a reopen reason) —
none of it is forwarded into `redacted_context` verbatim. Each `_redact_*` method is
an explicit allowlist of structured/coded/id/enum/numeric/boolean fields only; a
dropped field becomes at most a boolean presence marker, never its own raw content.
`ConsumeToolCompletedCommand.redaction_status` gets special treatment: 05's own
evidence-redaction marker is honored as the single source of truth for that one
event's own free-text fields — a `structured_output`/`summary` this consumer cannot
confirm 05 already redacted is dropped entirely rather than re-redacted a second,
different way.
"""

from __future__ import annotations

from evaluationimprovement.application.commands import (
    CollectOnlineSampleCommand,
    ConsumeMemoryRetrievalCompletedCommand,
    ConsumeTicketReopenedCommand,
    ConsumeTicketResolvedCommand,
    ConsumeToolCompletedCommand,
    ConsumeWorkflowCompletedCommand,
    ConsumeWorkflowFailedCommand,
)
from evaluationimprovement.application.ports_in import OnlineSampleUseCase
from evaluationimprovement.application.ports_out import ClockPort, ProcessedEventRepository
from evaluationimprovement.application.records import ProcessedEventRecord

CONSUMER_NAME = "consume_cross_domain_evaluation_event"

# 05's own confirmed-redacted marker (outbox_events.py: envelope.redaction_status.name)
# — anything else (including a genuinely absent value on an event type that predates
# a redaction_status field, e.g. memory.retrieval.completed.v1) is treated as
# not-confirmed-redacted.
_CONFIRMED_REDACTED_STATUSES = frozenset({"REDACTED", "NOT_REQUIRED"})


class ConsumeCrossDomainEventService:
    def __init__(self, processed_event_repository: ProcessedEventRepository, online_sample_port: OnlineSampleUseCase, clock: ClockPort) -> None:
        self._processed_event_repository = processed_event_repository
        self._online_sample_port = online_sample_port
        self._clock = clock

    def consume_ticket_resolved(self, command: ConsumeTicketResolvedCommand) -> bool:
        if self._processed_event_repository.is_processed(command.event_id, CONSUMER_NAME):
            return False
        self._collect(
            command.correlation_id, "ticket.resolved.v1", command.ticket_id,
            {"resolutionCode": command.resolution_code, "hasResolutionSummary": bool(command.resolution_summary)},
        )
        self._mark_processed(command.event_id, "ticket.resolved.v1")
        return True

    def consume_ticket_reopened(self, command: ConsumeTicketReopenedCommand) -> bool:
        if self._processed_event_repository.is_processed(command.event_id, CONSUMER_NAME):
            return False
        self._collect(
            command.correlation_id, "ticket.reopened.v1", command.ticket_id,
            {"reopenReasonCode": command.reopen_reason_code, "reopenCount": command.reopen_count},
        )
        self._mark_processed(command.event_id, "ticket.reopened.v1")
        return True

    def consume_workflow_completed(self, command: ConsumeWorkflowCompletedCommand) -> bool:
        if self._processed_event_repository.is_processed(command.event_id, CONSUMER_NAME):
            return False
        self._collect(
            command.correlation_id, "workflow.completed.v1", command.workflow_instance_id,
            {"toState": command.to_state, "workflowVersion": command.workflow_version},
        )
        self._mark_processed(command.event_id, "workflow.completed.v1")
        return True

    def consume_workflow_failed(self, command: ConsumeWorkflowFailedCommand) -> bool:
        if self._processed_event_repository.is_processed(command.event_id, CONSUMER_NAME):
            return False
        self._collect(
            command.correlation_id, "workflow.failed.v1", command.workflow_instance_id,
            {"toState": command.to_state, "workflowVersion": command.workflow_version, "hasFailureReason": bool(command.failure_reason)},
        )
        self._mark_processed(command.event_id, "workflow.failed.v1")
        return True

    def consume_tool_completed(self, command: ConsumeToolCompletedCommand) -> bool:
        if self._processed_event_repository.is_processed(command.event_id, CONSUMER_NAME):
            return False
        context = {"capabilityName": command.capability_name, "status": command.status, "errorCode": command.error_code}
        context["evidenceRedacted"] = command.redaction_status in _CONFIRMED_REDACTED_STATUSES
        self._collect(command.correlation_id, "tool.completed.v1", command.tool_request_id, context)
        self._mark_processed(command.event_id, "tool.completed.v1")
        return True

    def consume_memory_retrieval_completed(self, command: ConsumeMemoryRetrievalCompletedCommand) -> bool:
        if self._processed_event_repository.is_processed(command.event_id, CONSUMER_NAME):
            return False
        self._collect(
            command.correlation_id, "memory.retrieval.completed.v1", command.query_id,
            {"memoryType": command.memory_type, "resultCount": command.result_count, "aclScopeDenied": command.acl_scope_denied},
        )
        self._mark_processed(command.event_id, "memory.retrieval.completed.v1")
        return True

    def _collect(self, correlation_id: str, source_event_type: str, source_trace_ref: str, redacted_context: dict) -> None:
        self._online_sample_port.collect(CollectOnlineSampleCommand(
            candidate_id=None, target_version="unknown", source_event_type=source_event_type,
            source_trace_ref=source_trace_ref, redacted_context=redacted_context, actor="system:cross-domain-event-consumer",
            correlation_id=correlation_id,
        ))

    def _mark_processed(self, event_id: str, event_type: str) -> None:
        self._processed_event_repository.mark_processed(
            ProcessedEventRecord(event_id=event_id, consumer_name=CONSUMER_NAME, event_type=event_type, processed_at=self._clock.now()),
        )
