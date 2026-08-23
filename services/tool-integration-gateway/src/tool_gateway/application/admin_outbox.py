"""SPEC-TG-028 "Outbox Poison Replay Admin Repair" 10-failure-handling
§"Poison Request": "outbox publication fails beyond threshold" enters poison
handling; "Poison requests are not executed automatically again and require
admin audit." 08-transaction-and-outbox §"Outbox Publisher": "move records
beyond threshold to dead-letter outbox state" — ``PublishOutboxService``
already did that (SPEC-TG-002/003); this module is the missing other half:
an admin can actually see and repair what landed there. Not one of the seven
``application/`` filenames 13-package-and-class-design literally lists —
added the same way ``application.register_connector`` extended that set for
its own admin surface.

Only DEAD_LETTER rows are replayable — PENDING/PUBLISHED rows have no repair
to make (still pending is not stuck; published already succeeded).
"""

from __future__ import annotations

import uuid

from tool_gateway.application.audit import AuditRecorder
from tool_gateway.application.exceptions import OutboxRecordNotDeadLetterException, OutboxRecordNotFoundException
from tool_gateway.application.views import OutboxRecordView
from tool_gateway.domain.enums import OutboxStatus
from tool_gateway.ports.storage_port import AuditRecordRepository, ClockPort, OutboxRepository


class AdminOutboxService:
    def __init__(self, outbox_repository: OutboxRepository, audit_record_repository: AuditRecordRepository, clock: ClockPort) -> None:
        self._outbox_repository = outbox_repository
        self._clock = clock
        self._audit_recorder = AuditRecorder(audit_record_repository, clock)

    def list_dead_letter(self, limit: int) -> list[OutboxRecordView]:
        return [OutboxRecordView.from_domain(r) for r in self._outbox_repository.find_dead_letter(limit)]

    def replay(self, outbox_id: str, requested_by: str, correlation_id: str) -> OutboxRecordView:
        parsed_id = uuid.UUID(outbox_id)
        record = self._outbox_repository.find_by_id(parsed_id)
        if record is None:
            raise OutboxRecordNotFoundException(outbox_id)
        if record.status is not OutboxStatus.DEAD_LETTER:
            raise OutboxRecordNotDeadLetterException(outbox_id, record.status.name)

        now = self._clock.now()
        self._outbox_repository.requeue(parsed_id, now)
        self._audit_recorder.record(
            action="outbox_event_replayed", resource_type="OUTBOX_EVENT", resource_id=outbox_id,
            outcome="PENDING", actor_id=requested_by, correlation_id=correlation_id, detail=record.event_type,
        )
        requeued = self._outbox_repository.find_by_id(parsed_id)
        assert requeued is not None
        return OutboxRecordView.from_domain(requeued)
