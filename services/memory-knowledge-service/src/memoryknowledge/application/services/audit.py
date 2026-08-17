"""SPEC-MK-003 12-observability §"Audit Events": "审计动作: ingest_document,
approve_candidate, reject_candidate, publish_memory, supersede_memory, delete_memory."
Every application service that needs to append an audit row goes through this one
collaborator instead of constructing AuditRecordEntry/calling
AuditRecordRepository.append() directly — mirrors CommandIdempotencyGuard's own
"small, stateless, injected-everywhere helper" shape.

Recording never raises: a failure to append an audit row must not fail the primary
operation it is describing — mirrors agent-runtime-service's own AuditRecorder
exactly, including this same reasoning (this codebase's persistence architecture has
no cross-repository transaction to roll back into either; every repository already
opens its own short-lived session per call).
"""

from __future__ import annotations

import logging
import uuid

from memoryknowledge.application.ports_out import AuditRecordRepository, ClockPort
from memoryknowledge.application.records import AuditRecordEntry

logger = logging.getLogger(__name__)


class AuditRecorder:
    def __init__(self, audit_record_repository: AuditRecordRepository, clock: ClockPort) -> None:
        self._audit_record_repository = audit_record_repository
        self._clock = clock

    def record(
        self,
        audit_type: str,
        action: str,
        resource_type: str,
        resource_id: str,
        outcome: str,
        ticket_id: str | None = None,
        actor_type: str = "SYSTEM",
        actor_id: str | None = None,
        correlation_id: str | None = None,
        causation_id: str | None = None,
        detail: str = "{}",
    ) -> None:
        entry = AuditRecordEntry(
            id=uuid.uuid4(), audit_type=audit_type, action=action, resource_type=resource_type, resource_id=resource_id,
            ticket_id=ticket_id, actor_type=actor_type, actor_id=actor_id, outcome=outcome,
            correlation_id=correlation_id, causation_id=causation_id, detail=detail, occurred_at=self._clock.now(),
        )
        try:
            self._audit_record_repository.append(entry)
        except Exception:
            logger.warning(
                "action=audit_record_append status=failed audit_type=%s action_recorded=%s resource_type=%s resource_id=%s",
                audit_type, action, resource_type, resource_id, exc_info=True,
            )
