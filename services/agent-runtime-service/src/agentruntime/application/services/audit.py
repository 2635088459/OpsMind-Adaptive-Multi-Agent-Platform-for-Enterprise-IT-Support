"""SPEC-ARO-034 12-observability §"Audit Events": "审计事件必须可长期保存: workflow
transition, task transition, checkpoint created, tool request created, external event
consumed, pause/resume, recovery decision, admin intervention." Every application
service that needs to append an audit row goes through this one collaborator instead
of constructing AuditRecordEntry/calling AuditRecordRepository.append() directly —
mirrors CommandIdempotencyGuard's own "small, stateless, injected-everywhere helper"
shape (agentruntime.application.services.idempotency).

Recording never raises: a failure to append an audit row must not fail the primary
operation it is describing (unlike the sibling ticket-workflow-service's own
AuditRecordPort.append(), whose docstring requires the opposite — "failures must
propagate ... audit failure must roll back the caller's transaction" — a guarantee
this codebase's own persistence architecture doesn't otherwise provide anywhere; see
AuditRecordEntry's own docstring for the full reasoning). A logged warning is the
audit trail's own fallback of last resort.
"""

from __future__ import annotations

import logging
import uuid

from agentruntime.application.ports_out import AuditRecordRepository, ClockPort
from agentruntime.application.records import AuditRecordEntry
from agentruntime.domain.ids import TicketId, WorkflowInstanceId

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
        workflow_instance_id: WorkflowInstanceId | None = None,
        ticket_id: TicketId | None = None,
        actor_type: str = "SYSTEM",
        actor_id: str | None = None,
        correlation_id: str | None = None,
        causation_id: str | None = None,
        detail: str = "{}",
    ) -> None:
        entry = AuditRecordEntry(
            id=uuid.uuid4(), audit_type=audit_type, action=action, resource_type=resource_type, resource_id=resource_id,
            workflow_instance_id=workflow_instance_id, ticket_id=ticket_id, actor_type=actor_type, actor_id=actor_id,
            outcome=outcome, correlation_id=correlation_id, causation_id=causation_id, detail=detail,
            occurred_at=self._clock.now(),
        )
        try:
            self._audit_record_repository.append(entry)
        except Exception:
            logger.warning(
                "action=audit_record_append status=failed audit_type=%s action_recorded=%s resource_type=%s resource_id=%s",
                audit_type, action, resource_type, resource_id, exc_info=True,
            )
