"""Small shared helper so every application service records audit entries the
same shape — mirrors memory-knowledge-service's own
``application/services/audit.py`` (``AuditRecorder``) exactly.
"""

from __future__ import annotations

import uuid

from tool_gateway.domain.records import AuditRecordEntry
from tool_gateway.ports.storage_port import AuditRecordRepository, ClockPort


class AuditRecorder:
    def __init__(self, audit_record_repository: AuditRecordRepository, clock: ClockPort) -> None:
        self._audit_record_repository = audit_record_repository
        self._clock = clock

    def record(
        self, action: str, resource_type: str, resource_id: str, outcome: str, actor_id: str, correlation_id: str,
        ticket_id: str | None = None, detail: str | None = None, tool_request_id: str | None = None,
        execution_id: str | None = None, connector_id: str | None = None,
    ) -> None:
        self._audit_record_repository.append(AuditRecordEntry(
            audit_id=uuid.uuid4(), action=action, resource_type=resource_type, resource_id=resource_id,
            outcome=outcome, actor_id=actor_id, correlation_id=correlation_id, recorded_at=self._clock.now(),
            ticket_id=ticket_id, detail=detail, tool_request_id=tool_request_id, execution_id=execution_id,
            connector_id=connector_id,
        ))
