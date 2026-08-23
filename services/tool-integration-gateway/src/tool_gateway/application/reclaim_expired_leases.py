"""SPEC-TG-010 "Execution Scheduling Worker Lease" / 09-concurrency-and-
idempotency §"Worker Concurrent Claim": "Other workers may take over after
lease expiry." 10-failure-handling §"Gateway Crash Recovery": "Scan lease-
expired executions. Move INVOKING executions with expired lease to
reconciliation." Not one of the seven ``application/`` filenames 13-package-
and-class-design literally lists — added the same way this domain's other
event/admin surfaces were (see e.g. ``application.register_connector`` module
docstring).

A worker that died between ``claim()`` and ``begin_invoking()`` (CLAIMED or
PREPARING) never actually called the connector — no side effect was
committed, so the attempt is simply failed (``expire_lease()``/
``fail_preparing()``), never routed through reconciliation, which exists to
resolve *uncertain* outcomes only. A worker that died mid-INVOKING genuinely
left the outcome uncertain — treated identically to a connector-reported
timeout (``time_out()``), which is exactly what
``ReconciliationWorker``/``ReconcileExecutionService`` already knows how to
resolve (``ToolExecutionRepository.find_reconcilable()`` picks up TIMED_OUT
attempts).

What happens to the *ToolRequest* after a reclaim (retry vs terminal failure)
is deliberately left alone here, exactly like ``execute_tool_request``'s own
FAILED branch — automatic retry scheduling is phase-04 SPEC-TG-016's job (see
that module's own docstring for the same deferral).
"""

from __future__ import annotations

from tool_gateway.application.audit import AuditRecorder
from tool_gateway.application.commands import ReclaimExpiredLeasesCommand
from tool_gateway.domain.enums import ToolExecutionStatus
from tool_gateway.domain.tool_execution import ToolExecution
from tool_gateway.ports.storage_port import AuditRecordRepository, ClockPort, ToolExecutionRepository


class ReclaimExpiredLeasesService:
    def __init__(
        self, tool_execution_repository: ToolExecutionRepository, audit_record_repository: AuditRecordRepository, clock: ClockPort,
    ) -> None:
        self._tool_execution_repository = tool_execution_repository
        self._clock = clock
        self._audit_recorder = AuditRecorder(audit_record_repository, clock)

    def reclaim_expired_leases(self, command: ReclaimExpiredLeasesCommand) -> int:
        now = self._clock.now()
        expired = self._tool_execution_repository.find_lease_expired(now, command.batch_size)
        for execution in expired:
            reclaimed = self._reclaim_one(execution)
            self._tool_execution_repository.save(reclaimed, expected_status=None)
            self._audit_recorder.record(
                action="execution_lease_reclaimed", resource_type="TOOL_EXECUTION", resource_id=str(execution.execution_id),
                outcome=reclaimed.status.name, actor_id="lease-reclaim-worker", correlation_id=str(execution.execution_id),
            )
        return len(expired)

    @staticmethod
    def _reclaim_one(execution: ToolExecution) -> ToolExecution:
        if execution.status is ToolExecutionStatus.CLAIMED:
            return execution.expire_lease()
        if execution.status is ToolExecutionStatus.PREPARING:
            return execution.fail_preparing()
        return execution.time_out()
