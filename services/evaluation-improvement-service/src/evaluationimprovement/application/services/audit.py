"""11-security §"审计": dataset publish/deprecate, run create/cancel/finalize, gate
policy change, candidate create/reject/approval-request/canary/rollback, and
sensitive-evidence access must all write audit. Every application service that needs
to append an audit row goes through this one collaborator instead of constructing
AuditRecordEntry/calling AuditRecordRepository.append() directly.

Recording never raises: a failure to append an audit row must not fail the primary
operation it is describing — mirrors memory-knowledge-service's own AuditRecorder
exactly.
"""

from __future__ import annotations

import logging
import uuid

from evaluationimprovement.application.ports_out import AuditRecordRepository, ClockPort
from evaluationimprovement.application.records import AuditRecordEntry

logger = logging.getLogger(__name__)


class AuditRecorder:
    def __init__(self, audit_record_repository: AuditRecordRepository, clock: ClockPort) -> None:
        self._audit_record_repository = audit_record_repository
        self._clock = clock

    def record(
        self, action: str, resource_type: str, resource_id: str, actor: str, outcome: str,
        correlation_id: str | None = None, detail: str = "{}",
    ) -> None:
        entry = AuditRecordEntry(
            id=uuid.uuid4(), action=action, resource_type=resource_type, resource_id=resource_id, actor=actor,
            outcome=outcome, correlation_id=correlation_id, detail=detail, occurred_at=self._clock.now(),
        )
        try:
            self._audit_record_repository.append(entry)
        except Exception:
            logger.warning(
                "action=audit_record_append status=failed action_recorded=%s resource_type=%s resource_id=%s",
                action, resource_type, resource_id, exc_info=True,
            )
