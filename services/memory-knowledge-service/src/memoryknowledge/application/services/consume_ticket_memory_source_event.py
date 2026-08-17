"""13-package-and-class-design §"Application Layer": ConsumeTicketMemorySourceEventService,
the sole implementation of TicketMemorySourceEventConsumerPort. SPEC-MK-010
06-event-contracts: consumes `ticket.resolved.v1` and `ticket.closed.v1`
(02-ticket-workflow's own PUB-012/PUB-013) and triggers candidate extraction via
ExtractMemoryCandidateUseCase — 04-use-cases UC-04 ("从已解决 Ticket 抽取 Memory
Candidate") steps 1-2 ("拉取 ticket summary...", "生成 candidate").

08-transaction-and-outbox §"Candidate Extraction Transaction" step 1: "验证 consumed
event 未处理" — event-level dedup (eventId + consumerName) happens here, before
delegating to ExtractMemoryCandidateService, which separately enforces its own
content-level dedup (sourceHash + memoryType — SPEC-MK-011's own scope). The two are
independent layers, not a substitute for one another: this one stops literal event
redelivery; that one stops two different events from producing two candidates over
the same underlying evidence.

10-failure-handling §"Poison Event": "不标记 processed，除非明确 quarantine" — unlike
agent-runtime-service's own ConsumeTicketCreatedService/ConsumeTicketCycleEventService
(which mark_processed unconditionally in a `finally`, tolerating no retry at all), this
service only marks an event processed *after* a successful extraction: a malformed or
otherwise unprocessable event must stay retryable, per this domain's own explicit
failure-handling rule — a deliberate divergence from the agent-runtime-service
precedent, not an oversight.

SPEC-MK-029 10-failure-handling §"Poison Event" steps 1-2 ("写入 poison event 表",
"不推进 Workflow"): this domain's own event router already validates payload *shape*
with typed Pydantic request schemas before this service ever runs, so the one
invariant-violation this layer can actually raise is
IdempotencyKeyReusedException — the same idempotency_key arriving with genuinely
different candidate content, which is exactly "违反不变量" in this architecture's own
terms. Caught here, recorded to PoisonEventRepository (see
PoisonEventRecord's own docstring for the full reasoning), and re-raised as
PoisonMemorySourceEventException rather than the raw exception — never marked
processed either way, so this is a continuation of the existing rule above, not a
change to it.

ticket.closed.v1's own 06-event-contracts purpose ("确认 outcome，提升或降低 candidate
usefulness") is deliberately *not* implemented here as an in-place usefulness_score
mutation: no LLD section defines the adjustment algorithm (how much, based on what
signal), and `evaluation.completed.v1` is separately and explicitly named as "用途：
更新 memory usefulness score" — fabricating a scoring formula neither section specifies
would be inventing behavior, not implementing it. This consumer instead treats
ticket.closed.v1 the same as ticket.resolved.v1: a second, independent piece of
evidence that can produce its own EPISODIC candidate (naturally deduplicated against a
resolved-triggered candidate over the same content via source_hash+memory_type if the
text coincides).
"""

from __future__ import annotations

import dataclasses
import json
import uuid

from memoryknowledge.application.commands import (
    ConsumeTicketClosedCommand,
    ConsumeTicketResolvedCommand,
    ExtractMemoryCandidateCommand,
)
from memoryknowledge.application.exceptions import IdempotencyKeyReusedException, PoisonMemorySourceEventException
from memoryknowledge.application.ports_in import ExtractMemoryCandidateUseCase
from memoryknowledge.application.ports_out import ClockPort, PoisonEventRepository, ProcessedEventRepository
from memoryknowledge.application.records import PoisonEventRecord
from memoryknowledge.domain.enums import MemoryType
from memoryknowledge.domain.ids import IdempotencyKey
from memoryknowledge.domain.values import SourceRef

# SPEC-MK-010 09-concurrency-and-idempotency §"消费事件幂等": this service's own identity
# in the (event_id, consumer_name) dedup key. One constant for both event types it
# handles — one logical consumer, mirroring agent-runtime-service's own
# ConsumeTicketCycleEventService shape for its own two-event-type consumer.
CONSUMER_NAME = "consume_ticket_memory_source_event"


class ConsumeTicketMemorySourceEventService:
    def __init__(
        self, processed_event_repository: ProcessedEventRepository, extract_memory_candidate_port: ExtractMemoryCandidateUseCase,
        clock: ClockPort, poison_event_repository: PoisonEventRepository,
    ) -> None:
        self._processed_event_repository = processed_event_repository
        self._extract_memory_candidate_port = extract_memory_candidate_port
        self._clock = clock
        self._poison_event_repository = poison_event_repository

    def consume_resolved(self, command: ConsumeTicketResolvedCommand) -> bool:
        if self._processed_event_repository.is_processed(command.event_id, CONSUMER_NAME):
            return False

        candidate_text = f"Ticket resolved (resolutionCode={command.resolution_code}): {command.resolution_summary}"
        self._extract(
            ExtractMemoryCandidateCommand(
                memory_type=MemoryType.EPISODIC,
                source_refs=(SourceRef(source_type="ticket", source_id=str(command.ticket_id), field_path="resolution"),),
                candidate_text=candidate_text,
                idempotency_key=IdempotencyKey(f"ticket-resolved:{command.ticket_id}:{command.ticket_cycle_id}"),
                extracted_by="ticket-resolved-consumer",
            ),
            command.event_id, "ticket.resolved.v1",
        )
        # Marked only after a successful extraction — see this module's own docstring
        # for why that's a deliberate divergence from the agent-runtime-service
        # precedent's unconditional `finally: mark_processed(...)`.
        self._processed_event_repository.mark_processed(command.event_id, CONSUMER_NAME, self._clock.now(), "ticket.resolved.v1")
        return True

    def consume_closed(self, command: ConsumeTicketClosedCommand) -> bool:
        if self._processed_event_repository.is_processed(command.event_id, CONSUMER_NAME):
            return False

        candidate_text = f"Ticket closed (closeReasonCode={command.close_reason_code}): {command.close_reason}"
        self._extract(
            ExtractMemoryCandidateCommand(
                memory_type=MemoryType.EPISODIC,
                source_refs=(SourceRef(source_type="ticket", source_id=str(command.ticket_id), field_path="closure"),),
                candidate_text=candidate_text,
                idempotency_key=IdempotencyKey(f"ticket-closed:{command.ticket_id}:{command.ticket_cycle_id}"),
                extracted_by="ticket-closed-consumer",
            ),
            command.event_id, "ticket.closed.v1",
        )
        self._processed_event_repository.mark_processed(command.event_id, CONSUMER_NAME, self._clock.now(), "ticket.closed.v1")
        return True

    def _extract(self, command: ExtractMemoryCandidateCommand, event_id: str, event_type: str) -> None:
        try:
            self._extract_memory_candidate_port.extract(command)
        except IdempotencyKeyReusedException as exc:
            now = self._clock.now()
            payload = {
                "memory_type": command.memory_type.name, "candidate_text": command.candidate_text,
                "idempotency_key": str(command.idempotency_key), "extracted_by": command.extracted_by,
                "source_refs": [dataclasses.asdict(r) for r in command.source_refs],
            }
            self._poison_event_repository.record(PoisonEventRecord(
                id=uuid.uuid4(), event_id=event_id, consumer_name=CONSUMER_NAME, event_type=event_type,
                payload=json.dumps(payload), error_message=str(exc), occurred_at=now, recorded_at=now,
            ))
            raise PoisonMemorySourceEventException(event_id, str(exc)) from exc
