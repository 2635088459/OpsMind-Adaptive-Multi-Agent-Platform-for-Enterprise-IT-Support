"""13-package-and-class-design §"Application Layer": RecoverExpiredLeaseTasksService, the
sole implementation of LeaseRecoveryPort. SPEC-ARO-029 10-failure-handling §"Runtime 崩溃后
怎么恢复" step 5: "对 CLAIMED/RUNNING 且 lease 过期的 task 做 retry 或 stale 标记" — the
lease-expiry half of phase-08's reconciliation sweep RecoverWorkflowService's own docstring
already named but deliberately left out of SPEC-ARO-028 ("lease expiry requeue... is
phase-08").

Two 10-failure-handling sections disagree about the terminal state once retries are
exhausted: the recovery-worker step list quoted above says "retry 或 stale 标记" (STALE),
while a separate "Retry Policy" section says exhausted retries land in FAILED_FINAL.
Resolved with the user (AskUserQuestion, before writing any code) in favor of the more
specific recovery-worker step: exhausted -> STALE, reusing SPEC-ARO-016's existing
domain.agent_task.mark_stale() unchanged — from this aggregate's point of view, "the lease
that would have produced a legitimate result already expired" and "the task exhausted its
retry budget" are the same kind of stale-outcome fact complete_agent_task.py's own
_mark_stale() already persists for a different trigger (stale workflowVersion/
pauseGeneration). A task with attempts remaining instead goes through the new
domain.agent_task.retry_after_lease_expiry(), back to READY.

SPEC-ARO-031 05-api-contracts §"Admin API": "retry failed task" adds retry_task() —
the admin-triggered, single-task counterpart to scan_and_recover(), for an operator
who has already identified a stuck CLAIMED/RUNNING task and does not want to wait for
its lease to actually expire (an explicit override of the *wait*, not of the
CLAIMED/RUNNING precondition itself — a terminal task, e.g. FAILED_FINAL, is still
never reachable this way, per 03-state-machine's own "不可重试失败", confirmed with the
user before this spec's admin capabilities were scoped). Unlike scan_and_recover()'s
own tolerant _retry()/_mark_stale() (silently skipping a lost race, since a batch scan
has no single caller to report back to), retry_task() lets
InvalidAgentTaskTransitionException/AgentTaskVersionConflictException propagate as a
real error — silently no-op-ing an explicit, single-target admin request would hide a
real failure from the operator who asked for it.
"""

from __future__ import annotations

import dataclasses
import logging
from datetime import datetime

from opentelemetry import trace

from agentruntime.application.commands import RetryAgentTaskCommand
from agentruntime.application.exceptions import AgentTaskNotFoundException, AgentTaskVersionConflictException
from agentruntime.application.ports_out import AgentTaskRepository, ClockPort, WorkflowInstanceRepository
from agentruntime.application.records import AgentTaskRecord, WorkflowInstanceRecord
from agentruntime.application.services.audit import AuditRecorder
from agentruntime.application.telemetry import RuntimeTelemetry
from agentruntime.application.views import AgentTaskView, LeaseRecoveryReport
from agentruntime.domain import agent_task
from agentruntime.domain.exceptions import InvalidAgentTaskTransitionException
from agentruntime.domain.ids import TicketCycleId, TicketId, WorkflowInstanceId

_DEFAULT_SCAN_BATCH_SIZE = 50

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)


class RecoverExpiredLeaseTasksService:
    def __init__(
        self,
        agent_task_repository: AgentTaskRepository,
        workflow_instance_repository: WorkflowInstanceRepository,
        clock: ClockPort,
        telemetry: RuntimeTelemetry,
        audit_recorder: AuditRecorder,
    ) -> None:
        self._agent_task_repository = agent_task_repository
        self._workflow_instance_repository = workflow_instance_repository
        self._clock = clock
        self._telemetry = telemetry
        self._audit_recorder = audit_recorder

    def scan_and_recover(self, batch_size: int = _DEFAULT_SCAN_BATCH_SIZE) -> LeaseRecoveryReport:
        """10-failure-handling step 5, applied across every CLAIMED/RUNNING Agent Task
        whose lease has already expired. Each candidate is routed by its own
        attempt/max_attempts budget: attempts remaining -> retry back to READY; exhausted
        -> STALE. A lost race against something else that already moved a candidate on
        (e.g. a legitimately-late worker submission landing between the scan read and
        this save, or a concurrent recovery pass) is silently skipped, not an error —
        the same tolerance RecoverWorkflowService.scan_and_recover() already applies.
        """
        with tracer.start_as_current_span("recovery.decision"):
            now = self._clock.now()
            expired = self._agent_task_repository.find_expired_leases(now, batch_size)

            # SPEC-ARO-036 12-observability §"日志": ticketId/ticketCycleId must be on
            # every log line — cached per workflow_instance_id across this batch (mirrors
            # ClaimAgentTaskService.claim_ready()'s own workflow_cache), since several
            # expired-lease tasks scanned together commonly belong to the same workflow.
            workflow_cache: dict[WorkflowInstanceId, WorkflowInstanceRecord | None] = {}
            retried = staled = 0
            for task in expired:
                if task.attempt < task.max_attempts:
                    if self._retry(task, now, workflow_cache):
                        retried += 1
                elif self._mark_stale(task, now, workflow_cache):
                    staled += 1

            return LeaseRecoveryReport(scanned=len(expired), retried=retried, staled=staled, scanned_at=now)

    def retry_task(self, command: RetryAgentTaskCommand) -> AgentTaskView:
        """SPEC-ARO-031 05-api-contracts §"Admin API": "retry failed task" — see this
        module's own docstring for the full rationale. Routed by the same
        attempt/max_attempts budget scan_and_recover() uses, but errors propagate
        instead of being swallowed.
        """
        with tracer.start_as_current_span("recovery.decision"):
            task = self._agent_task_repository.find_by_id(command.agent_task_id)
            if task is None:
                raise AgentTaskNotFoundException(str(command.agent_task_id))

            now = self._clock.now()
            workflow = self._workflow_instance_repository.find_by_id(task.workflow_instance_id)
            ticket_id, ticket_cycle_id = self._ticket_ids(workflow)
            if task.attempt < task.max_attempts:
                event = agent_task.retry_after_lease_expiry(
                    task.id, task.workflow_instance_id, task.state, task.task_version, task.attempt, task.max_attempts, now,
                )
                saved = self._agent_task_repository.save(dataclasses.replace(
                    task, state=event.to_state, task_version=event.task_version, attempt=event.attempt,
                    lease_expires_at=None, updated_at=now,
                ))
                logger.info(
                    "action=admin_retry_task agent_task_id=%s workflow_instance_id=%s ticket_id=%s ticket_cycle_id=%s "
                    "outcome=retried attempt=%s max_attempts=%s",
                    task.id, task.workflow_instance_id, ticket_id, ticket_cycle_id, event.attempt, task.max_attempts,
                )
                self._telemetry.record_task_retry()
                outcome_action = "admin_retry_task_retried"
            else:
                event = agent_task.mark_stale(
                    task.id, task.workflow_instance_id, task.state, task.task_version,
                    "admin retry: attempt budget already exhausted", now,
                )
                saved = self._agent_task_repository.save(dataclasses.replace(
                    task, state=event.to_state, task_version=event.task_version, lease_expires_at=None, updated_at=now,
                ))
                logger.info(
                    "action=admin_retry_task agent_task_id=%s workflow_instance_id=%s ticket_id=%s ticket_cycle_id=%s "
                    "outcome=staled attempt=%s max_attempts=%s",
                    task.id, task.workflow_instance_id, ticket_id, ticket_cycle_id, task.attempt, task.max_attempts,
                )
                self._telemetry.record_task_lease_expired()
                outcome_action = "admin_retry_task_staled"

            self._audit_recorder.record(
                "ADMIN_INTERVENTION", outcome_action, "AgentTask", str(task.id), "SUCCESS",
                workflow_instance_id=task.workflow_instance_id, ticket_id=ticket_id, actor_type="ADMIN",
            )
            return AgentTaskView.from_record(saved)

    def _retry(self, task: AgentTaskRecord, now: datetime, workflow_cache: dict[WorkflowInstanceId, WorkflowInstanceRecord | None]) -> bool:
        try:
            event = agent_task.retry_after_lease_expiry(
                task.id, task.workflow_instance_id, task.state, task.task_version, task.attempt, task.max_attempts, now,
            )
        except InvalidAgentTaskTransitionException:
            return False

        try:
            self._agent_task_repository.save(dataclasses.replace(
                task, state=event.to_state, task_version=event.task_version, attempt=event.attempt,
                lease_expires_at=None, updated_at=now,
            ))
        except AgentTaskVersionConflictException:
            return False

        ticket_id, ticket_cycle_id = self._ticket_ids(self._resolve_workflow(task.workflow_instance_id, workflow_cache))
        logger.info(
            "action=lease_recovery_retry agent_task_id=%s workflow_instance_id=%s ticket_id=%s ticket_cycle_id=%s "
            "attempt=%s max_attempts=%s",
            task.id, task.workflow_instance_id, ticket_id, ticket_cycle_id, event.attempt, task.max_attempts,
        )
        self._telemetry.record_task_retry()
        self._audit_recorder.record(
            "RECOVERY_DECISION", "lease_recovery_retry", "AgentTask", str(task.id), "SUCCESS",
            workflow_instance_id=task.workflow_instance_id, ticket_id=ticket_id, actor_type="SYSTEM",
        )
        return True

    def _mark_stale(self, task: AgentTaskRecord, now: datetime, workflow_cache: dict[WorkflowInstanceId, WorkflowInstanceRecord | None]) -> bool:
        """Mirrors complete_agent_task.py's own _mark_stale(): clears only
        lease_expires_at (so is_claimable()'s AlreadyClaimed guard does not wait out the
        original worker's now-irrelevant lease duration), leaves worker_id/lease_token in
        place as an audit trail of who held the claim right before it went stale.
        """
        try:
            event = agent_task.mark_stale(
                task.id, task.workflow_instance_id, task.state, task.task_version,
                "recovery scan: lease expired and retry attempts exhausted", now,
            )
        except InvalidAgentTaskTransitionException:
            return False

        try:
            self._agent_task_repository.save(dataclasses.replace(
                task, state=event.to_state, task_version=event.task_version, lease_expires_at=None, updated_at=now,
            ))
        except AgentTaskVersionConflictException:
            return False

        ticket_id, ticket_cycle_id = self._ticket_ids(self._resolve_workflow(task.workflow_instance_id, workflow_cache))
        logger.info(
            "action=lease_recovery_stale agent_task_id=%s workflow_instance_id=%s ticket_id=%s ticket_cycle_id=%s "
            "attempt=%s max_attempts=%s",
            task.id, task.workflow_instance_id, ticket_id, ticket_cycle_id, task.attempt, task.max_attempts,
        )
        self._telemetry.record_task_lease_expired()
        self._audit_recorder.record(
            "RECOVERY_DECISION", "lease_recovery_stale", "AgentTask", str(task.id), "SUCCESS",
            workflow_instance_id=task.workflow_instance_id, ticket_id=ticket_id, actor_type="SYSTEM",
        )
        return True

    def _resolve_workflow(
        self, workflow_instance_id: WorkflowInstanceId, workflow_cache: dict[WorkflowInstanceId, WorkflowInstanceRecord | None]
    ) -> WorkflowInstanceRecord | None:
        if workflow_instance_id not in workflow_cache:
            workflow_cache[workflow_instance_id] = self._workflow_instance_repository.find_by_id(workflow_instance_id)
        return workflow_cache[workflow_instance_id]

    def _ticket_ids(self, workflow: WorkflowInstanceRecord | None) -> tuple[TicketId | None, TicketCycleId | None]:
        # Defensive only: an expired-lease task's own workflow_instance_id should always
        # resolve (SPEC-ARO-007's own foreign-key invariant) — None here would mean the
        # workflow was concurrently deleted, which nothing in this codebase actually does.
        if workflow is None:
            return None, None
        return workflow.ticket_id, workflow.ticket_cycle_id
