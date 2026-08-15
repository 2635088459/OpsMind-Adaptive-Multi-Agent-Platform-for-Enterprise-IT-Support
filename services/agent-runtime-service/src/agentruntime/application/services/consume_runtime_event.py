"""13-package-and-class-design §"Application Layer": ConsumeRuntimeEventService, the
sole implementation of RuntimeEventConsumerPort. 02-business-invariants §"Event
Handling Invariants": "Every consumed event must be checked against or written to
processed_events" and SPEC-ARO-001 event-contract: "Duplicate/stale/invalid events
must not advance Workflow again."

Per-event-type application (approval decisions, tool results, etc.) was out of
SPEC-ARO-001's own scope; this service owns exactly the dedup/staleness gate every
consumer must pass through first, then dispatches to whichever specific consumer owns
that event_type: tool.completed.v1 (ConsumeToolResultService, SPEC-ARO-020),
approval.granted.v1 (ConsumeApprovalService, SPEC-ARO-021), and
verification.completed.v1 (ConsumeVerificationService, SPEC-ARO-022).

SPEC-ARO-024 10-failure-handling §"Poison Event" formalizes the three-way
duplicate/stale/invalid classification SPEC-ARO-001's own event-contract already named:
duplicate (processed_event_repository.is_processed, above) and stale
(StaleRuntimeEventException, below) were already well-classified; "invalid" — an opaque
payload the type-specific consumer could not even parse — was not. A payload-shape
failure (malformed JSON, a missing required field, an unparsable id) is distinguished
from a well-understood business rejection (WorkflowInstanceNotFoundException,
ToolRequestNotFoundException, ...) purely by exception type: the former is always one of
_POISON_EXCEPTION_TYPES given how every type-specific consumer's own apply() parses its
payload (json.loads, dict indexing, uuid.UUID(...)), since none of those consumers ever
constructs a domain command from untrusted payload strings deep enough to raise the same
exception types for a legitimate business reason.
"""

from __future__ import annotations

import json
import logging
import uuid

from opentelemetry import trace

from agentruntime.application.commands import RuntimeEventEnvelope
from agentruntime.application.exceptions import PoisonRuntimeEventException, StaleRuntimeEventException, WorkflowInstanceNotFoundException
from agentruntime.application.ports_out import ClockPort, PoisonEventRepository, ProcessedEventRepository, WorkflowInstanceRepository
from agentruntime.application.records import PoisonEventRecord
from agentruntime.application.services.audit import AuditRecorder
from agentruntime.application.services.consume_approval import ConsumeApprovalService
from agentruntime.application.services.consume_tool_result import ConsumeToolResultService
from agentruntime.application.services.consume_verification import ConsumeVerificationService
from agentruntime.application.telemetry import RuntimeTelemetry

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)

# SPEC-ARO-013 09-concurrency-and-idempotency §"消费事件幂等": this service's own identity
# in the (event_id, consumer_name) dedup key — distinct from ConsumeTicketCreatedService's,
# so the two never collide over a coincidentally-shared event_id. One constant for the
# whole service, not per event_type, since it is a single logical consumer regardless of
# which runtime event type (tool.completed, approval.granted, ...) flows through it.
CONSUMER_NAME = "consume_runtime_event"

# SPEC-ARO-020 06-event-contracts §"tool.completed.v1": no separate tool.failed.v1 is
# documented — a `status` field on this one event type carries COMPLETED or FAILED.
_TOOL_RESULT_EVENT_TYPE = "tool.completed.v1"

# SPEC-ARO-021 06-event-contracts §"approval.granted.v1": no separate approval.rejected.v1
# is documented — a `decision` field on this one event type carries the outcome.
_APPROVAL_EVENT_TYPE = "approval.granted.v1"

# SPEC-ARO-022 06-event-contracts §"verification.completed.v1": a `passed` boolean field
# on this one event type carries the outcome.
_VERIFICATION_EVENT_TYPE = "verification.completed.v1"

# SPEC-ARO-024 10-failure-handling §"Poison Event": the exception shapes a type-specific
# consumer's own payload parsing (json.loads, required-key indexing, uuid.UUID(...))
# raises on malformed input.
_POISON_EXCEPTION_TYPES = (json.JSONDecodeError, KeyError, ValueError, TypeError)


class ConsumeRuntimeEventService:
    def __init__(
        self,
        processed_event_repository: ProcessedEventRepository,
        workflow_instance_repository: WorkflowInstanceRepository,
        clock: ClockPort,
        consume_tool_result_service: ConsumeToolResultService,
        consume_approval_service: ConsumeApprovalService,
        consume_verification_service: ConsumeVerificationService,
        poison_event_repository: PoisonEventRepository,
        telemetry: RuntimeTelemetry,
        audit_recorder: AuditRecorder,
    ) -> None:
        self._processed_event_repository = processed_event_repository
        self._workflow_instance_repository = workflow_instance_repository
        self._clock = clock
        self._consume_tool_result_service = consume_tool_result_service
        self._consume_approval_service = consume_approval_service
        self._consume_verification_service = consume_verification_service
        self._poison_event_repository = poison_event_repository
        self._telemetry = telemetry
        self._audit_recorder = audit_recorder

    def consume(self, envelope: RuntimeEventEnvelope) -> bool:
        with tracer.start_as_current_span("event.consumed"):
            return self._consume_traced(envelope)

    def _consume_traced(self, envelope: RuntimeEventEnvelope) -> bool:
        if self._processed_event_repository.is_processed(envelope.event_id, CONSUMER_NAME):
            self._telemetry.record_event_duplicate(envelope.event_type)
            return False

        workflow = self._workflow_instance_repository.find_by_id(envelope.workflow_instance_id)
        if workflow is None:
            raise WorkflowInstanceNotFoundException(envelope.workflow_instance_id)

        if envelope.expected_workflow_version is not None and envelope.expected_workflow_version != workflow.workflow_version:
            self._mark_processed(envelope)
            self._telemetry.record_event_duplicate(envelope.event_type)
            raise StaleRuntimeEventException(envelope.event_id)

        try:
            if envelope.event_type == _TOOL_RESULT_EVENT_TYPE:
                self._consume_tool_result_service.apply(envelope.payload)
            elif envelope.event_type == _APPROVAL_EVENT_TYPE:
                self._consume_approval_service.apply(envelope.workflow_instance_id, envelope.payload)
            elif envelope.event_type == _VERIFICATION_EVENT_TYPE:
                self._consume_verification_service.apply(envelope.workflow_instance_id, envelope.payload)
        except _POISON_EXCEPTION_TYPES as exc:
            # Deliberately NOT mark_processed: PoisonEventRecord's own docstring — a
            # poisoned delivery must stay replayable under this event_id once fixed.
            self._record_poison_event(envelope, exc)
            raise PoisonRuntimeEventException(envelope.event_id, str(exc)) from exc
        except Exception:
            # A well-classified business rejection (not-found, invalid transition, ...)
            # — 10-failure-handling: "a malformed or otherwise unprocessable event must
            # not be retried forever" still applies to these, unchanged from before this
            # spec.
            self._mark_processed(envelope)
            raise
        else:
            self._mark_processed(envelope)

        self._telemetry.record_event_consumed(envelope.event_type)
        self._audit_recorder.record(
            "EXTERNAL_EVENT_CONSUMED", "consume_runtime_event", "RuntimeEvent", envelope.event_id, "SUCCESS",
            workflow_instance_id=envelope.workflow_instance_id, ticket_id=envelope.ticket_id, actor_type="EVENT_CONSUMER",
            correlation_id=str(envelope.correlation_id), causation_id=str(envelope.causation_id),
            detail=json.dumps({"event_type": envelope.event_type}),
        )
        return True

    def _record_poison_event(self, envelope: RuntimeEventEnvelope, exc: Exception) -> None:
        now = self._clock.now()
        self._poison_event_repository.record(PoisonEventRecord(
            id=uuid.uuid4(), event_id=envelope.event_id, consumer_name=CONSUMER_NAME, event_type=envelope.event_type,
            payload=envelope.payload, error_message=str(exc), occurred_at=envelope.occurred_at, recorded_at=now,
        ))
        # Stand-in for "发布 observability alert" (10-failure-handling step 3) — a real
        # alerting integration is out of this spec's scope, the same "not our job yet"
        # deferral already used for secret-scanning/broker publishing elsewhere.
        # SPEC-ARO-036 12-observability §"日志": every field the envelope itself already
        # carries safely (workflow_instance_id/ticket_id/correlation_id/causation_id) is
        # included; ticket_cycle_id is deliberately NOT looked up here — RuntimeEventEnvelope
        # has no such field, and this fires exactly when payload parsing already failed, so
        # a second repository call on this failure path would risk masking the original
        # poison detection behind a new one.
        logger.error(
            "poison runtime event event_id=%s event_type=%s workflow_instance_id=%s ticket_id=%s "
            "correlation_id=%s causation_id=%s error=%s",
            envelope.event_id, envelope.event_type, envelope.workflow_instance_id, envelope.ticket_id,
            envelope.correlation_id, envelope.causation_id, exc,
        )

    def _mark_processed(self, envelope: RuntimeEventEnvelope) -> None:
        self._processed_event_repository.mark_processed(
            envelope.event_id, CONSUMER_NAME, self._clock.now(), envelope.event_type, envelope.workflow_instance_id
        )
