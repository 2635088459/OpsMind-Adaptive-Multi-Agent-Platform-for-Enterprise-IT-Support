"""13-package-and-class-design §"Application Layer": RecoverWorkflowService, the sole
implementation of RecoveryPort. 02-business-invariants: "Runtime must recover from
checkpoints, leases, cursors, and outbox after crash" — recover() demonstrates that
recoverability for a single named instance by reconstructing a RecoveryReport purely
from persisted state, never from anything held only in memory.

SPEC-ARO-028 10-failure-handling §"Runtime 崩溃后怎么恢复" adds scan_and_recover(), the
batch "Recovery worker 周期性扫描非终态 Workflow Instance" this section describes — steps
1-2 of its own 7-step list ("读取最新 checkpoint", "检查 workflow state 与 checkpoint 是否一致").
Steps 3-5 (outbox replay, lease release/stale marking) are explicitly out of this spec's
own scope — SPEC-ARO-029/030 own them, per this module's own prior docstring ("A full
reconciliation sweep (lease expiry requeue, outbox redelivery) is phase-08"), and this
class's own job within phase-08 is specifically the checkpoint-restore half of it.

SPEC-ARO-031 05-api-contracts §"Admin API": "force recover workflow" adds
force_recover() — the admin-triggered, single-instance counterpart to
scan_and_recover(), for an operator who has already identified one specific stuck
instance and does not want to wait for the next scheduled scan. Reuses the exact same
narrow checkpoint-consistency check unchanged; never reaches a terminal instance
(03-state-machine: FAILED/COMPLETED/CANCELLED are one-way, confirmed with the user
before this spec's other admin capabilities were scoped).
"""

from __future__ import annotations

import logging

from opentelemetry import trace

from agentruntime.application.commands import FailWorkflowCommand, ForceRecoverWorkflowCommand, RecoveryCommand
from agentruntime.application.exceptions import (
    DefinitionVersionMismatchException,
    WorkflowInstanceNotFoundException,
    WorkflowInstanceVersionConflictException,
)
from agentruntime.application.ports_out import AgentTaskRepository, CheckpointRepository, ClockPort, WorkflowInstanceRepository
from agentruntime.application.records import WorkflowInstanceRecord
from agentruntime.application.services.audit import AuditRecorder
from agentruntime.application.services.fail_workflow import FailWorkflowService
from agentruntime.application.telemetry import RuntimeTelemetry
from agentruntime.application.views import RecoveryReport, RecoveryScanReport
from agentruntime.domain.enums import AgentTaskState, CheckpointType, WorkflowState
from agentruntime.domain.exceptions import InvalidWorkflowStateException
from agentruntime.domain.ids import IdempotencyKey, WorkflowInstanceId

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)

_DEFAULT_SCAN_BATCH_SIZE = 50


class RecoverWorkflowService:
    def __init__(
        self,
        workflow_instance_repository: WorkflowInstanceRepository,
        checkpoint_repository: CheckpointRepository,
        agent_task_repository: AgentTaskRepository,
        clock: ClockPort,
        fail_workflow_service: FailWorkflowService,
        telemetry: RuntimeTelemetry,
        audit_recorder: AuditRecorder,
    ) -> None:
        self._workflow_instance_repository = workflow_instance_repository
        self._checkpoint_repository = checkpoint_repository
        self._agent_task_repository = agent_task_repository
        self._clock = clock
        self._fail_workflow_service = fail_workflow_service
        self._telemetry = telemetry
        self._audit_recorder = audit_recorder

    def recover(self, command: RecoveryCommand) -> RecoveryReport:
        workflow = self._workflow_instance_repository.find_by_id(command.workflow_instance_id)
        if workflow is None:
            raise WorkflowInstanceNotFoundException(command.workflow_instance_id)

        if command.expected_definition_version is not None and command.expected_definition_version != workflow.definition_version:
            raise DefinitionVersionMismatchException(workflow.definition_version, command.expected_definition_version)

        recoverable_checkpoint_count = len(self._checkpoint_repository.find_by_workflow_instance_id(workflow.id))
        open_lease_count = self._open_lease_count(workflow.id)

        return RecoveryReport(
            workflow_instance_id=workflow.id, state=workflow.state, workflow_version=workflow.workflow_version,
            definition_version=workflow.definition_version, recoverable_checkpoint_count=recoverable_checkpoint_count,
            open_lease_count=open_lease_count, recovered_at=self._clock.now(),
        )

    def scan_and_recover(self, batch_size: int = _DEFAULT_SCAN_BATCH_SIZE) -> RecoveryScanReport:
        """10-failure-handling steps 1-2, applied across every non-terminal Workflow
        Instance rather than one named by id. Steps 3-5 (lease/outbox) are deliberately
        not attempted here — SPEC-ARO-029/030's own job.
        """
        with tracer.start_as_current_span("recovery.decision"):
            now = self._clock.now()
            instances = self._workflow_instance_repository.find_non_terminal(batch_size)

            checkpoint_inconsistent = 0
            for workflow in instances:
                if self._is_checkpoint_inconsistent(workflow):
                    if self._flag_checkpoint_inconsistent(workflow):
                        checkpoint_inconsistent += 1
                        # SPEC-ARO-036 12-observability §"Audit Events": "recovery decision"
                        # — audited only when the scan actually flagged something, mirroring
                        # RecoverExpiredLeaseTasksService's own scan_and_recover() (its
                        # _retry()/_mark_stale() audit an action taken, not every candidate
                        # merely scanned).
                        self._audit_recorder.record(
                            "RECOVERY_DECISION", "scan_and_recover_workflows", "WorkflowInstance", str(workflow.id), "SUCCESS",
                            workflow_instance_id=workflow.id, ticket_id=workflow.ticket_id, actor_type="SYSTEM",
                        )
                    self._telemetry.record_workflow_recovered(checkpoint_inconsistent=True)
                else:
                    self._telemetry.record_workflow_recovered(checkpoint_inconsistent=False)

            logger.info(
                "action=scan_and_recover_workflows status=completed scanned=%s checkpoint_inconsistent=%s",
                len(instances), checkpoint_inconsistent,
            )
            return RecoveryScanReport(scanned=len(instances), checkpoint_inconsistent=checkpoint_inconsistent, scanned_at=now)

    def force_recover(self, command: ForceRecoverWorkflowCommand) -> RecoveryScanReport:
        """SPEC-ARO-031 05-api-contracts §"Admin API": "force recover workflow" — applies
        scan_and_recover()'s own checkpoint-consistency check to the one named instance
        an operator has already identified as stuck, immediately rather than waiting for
        the next scheduled batch sweep to reach it. Deliberately reuses the exact same
        narrow check, not a broader "repair" heuristic — see
        _is_checkpoint_inconsistent()'s own docstring for why guessing a wider one would
        be unfounded speculation.
        """
        with tracer.start_as_current_span("recovery.decision"):
            workflow = self._workflow_instance_repository.find_by_id(command.workflow_instance_id)
            if workflow is None:
                raise WorkflowInstanceNotFoundException(command.workflow_instance_id)

            now = self._clock.now()
            is_inconsistent = self._is_checkpoint_inconsistent(workflow)
            checkpoint_inconsistent = 1 if is_inconsistent and self._flag_checkpoint_inconsistent(workflow) else 0
            self._telemetry.record_workflow_recovered(checkpoint_inconsistent=bool(checkpoint_inconsistent))

            logger.info(
                "action=force_recover_workflow status=completed workflow_instance_id=%s ticket_id=%s ticket_cycle_id=%s "
                "checkpoint_inconsistent=%s",
                workflow.id, workflow.ticket_id, workflow.ticket_cycle_id, checkpoint_inconsistent,
            )
            # SPEC-ARO-036 12-observability §"Audit Events": "admin intervention" — an
            # explicit, operator-triggered single-instance action, audited unconditionally
            # (unlike scan_and_recover()'s own RECOVERY_DECISION, which only records when a
            # real inconsistency was actually flagged) — mirrors RecoverExpiredLeaseTasksService
            # .retry_task()'s own ADMIN_INTERVENTION convention exactly.
            self._audit_recorder.record(
                "ADMIN_INTERVENTION", "force_recover_workflow", "WorkflowInstance", str(workflow.id), "SUCCESS",
                workflow_instance_id=workflow.id, ticket_id=workflow.ticket_id, actor_type="ADMIN",
            )
            return RecoveryScanReport(scanned=1, checkpoint_inconsistent=checkpoint_inconsistent, scanned_at=now)

    def _is_checkpoint_inconsistent(self, workflow: WorkflowInstanceRecord) -> bool:
        """10-failure-handling step 2: "检查 workflow state 与 checkpoint 是否一致." The one
        state/checkpoint-type pairing this domain's own checkpoint types make unambiguous:
        a PAUSED workflow's own latest checkpoint must be the PAUSE_POINT SPEC-ARO-012's
        Pause Transaction writes — ResumeWorkflowService already requires this exact
        pairing before letting a resume proceed (PauseCheckpointNotFoundException); this
        scanner catches the same inconsistency proactively rather than waiting for the
        next resume attempt to discover it. Deliberately narrow: every other
        state/checkpoint-type combination in this domain has no single unambiguous
        "expected type" to check against, and guessing one would be exactly the vague,
        unfounded heuristic this spec's own scope discipline avoids.
        """
        if workflow.state is not WorkflowState.PAUSED:
            return False
        latest = self._checkpoint_repository.find_latest_by_workflow_instance_id(workflow.id)
        return latest is None or latest.type is not CheckpointType.PAUSE_POINT

    def _flag_checkpoint_inconsistent(self, workflow: WorkflowInstanceRecord) -> bool:
        """10-failure-handling step 7: "如果发现无法判断的副作用窗口，进入 FAILED... 并发布审计
        事件" — reuses the existing FailWorkflowService (already idempotent, already
        outbox-publishing) rather than a bespoke transition; the outbox publish itself is
        the audit event, the same reuse this domain's other consumers already rely on.
        """
        idempotency_key = IdempotencyKey(f"recovery-scan-inconsistent:{workflow.id}")
        try:
            self._fail_workflow_service.fail(FailWorkflowCommand(
                workflow.id, idempotency_key,
                "recovery scan: PAUSED workflow's latest checkpoint is not a PAUSE_POINT (state/checkpoint inconsistency)",
            ))
            return True
        except (WorkflowInstanceVersionConflictException, InvalidWorkflowStateException):
            # Lost a race against something else that already moved this workflow on
            # (e.g. a concurrent resume/cancel) — the inconsistency this scan observed is
            # already stale, not a real finding.
            return False

    def _open_lease_count(self, workflow_instance_id: WorkflowInstanceId) -> int:
        return sum(
            1
            for task in self._agent_task_repository.find_by_workflow_instance_id(workflow_instance_id)
            if task.state in (AgentTaskState.CLAIMED, AgentTaskState.RUNNING) and task.is_lease_outstanding()
        )
