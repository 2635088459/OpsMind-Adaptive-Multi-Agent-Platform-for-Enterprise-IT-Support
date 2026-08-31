"""SPEC-EI-035 (langsmith-grader-outbox-failure-recovery) / 05-api-contracts
§"管理 API" / 10-failure-handling: "admin repair/replay API 有审计." AdminRecoveryService
wraps OutboxDispatchPort.dispatch_due_events() (an actor-less operational primitive —
CaseRunnerPort's own precedent) with the actor/audit an *admin-triggered* manual
replay needs, without adding an audit dependency to the plain operational port itself.
"""

from __future__ import annotations

from evaluationimprovement.application.ports_in import OutboxDispatchPort
from evaluationimprovement.application.ports_out import AuditRecordRepository, ClockPort
from evaluationimprovement.application.services.audit import AuditRecorder
from evaluationimprovement.application.views import DispatchReport


class AdminRecoveryService:
    def __init__(self, outbox_dispatch_port: OutboxDispatchPort, audit_record_repository: AuditRecordRepository, clock: ClockPort) -> None:
        self._outbox_dispatch_port = outbox_dispatch_port
        self._audit_recorder = AuditRecorder(audit_record_repository, clock)

    def dispatch_outbox_events(self, batch_size: int, actor: str, correlation_id: str) -> DispatchReport:
        report = self._outbox_dispatch_port.dispatch_due_events(batch_size)
        self._audit_recorder.record(
            action="admin_dispatch_outbox_events", resource_type="OUTBOX", resource_id="batch", actor=actor,
            outcome="SUCCESS", correlation_id=correlation_id,
            detail=f'{{"dispatched": {report.dispatched}, "failed": {report.failed}, "dead_lettered": {report.dead_lettered}}}',
        )
        return report
