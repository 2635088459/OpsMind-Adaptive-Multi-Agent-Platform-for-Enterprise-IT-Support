"""13-package-and-class-design §"Application Layer" / §"Class Boundaries": "Worker
claim must use a lease; after lease expiry the task can be claimed again."
02-business-invariants §"Agent Task Invariants": "All dependsOn tasks must complete
before execution" — resolved here against AgentTaskRepository since the pure domain
function must not query. 09-concurrency-and-idempotency §"Task Claim": "Workflow must
be in RUNNING" and "pauseGeneration must be copied into the task claim." SPEC-ARO-009
05-api-contracts "Claim Task" adds claim_ready(): the role-based batch claim a generic
Agent Worker pool actually polls with (claim() by exact workflow_instance_id+task_key
stays available for a caller that already knows precisely which task it wants).
"""

from __future__ import annotations

import dataclasses
import logging
from datetime import timedelta

from opentelemetry import trace

from agentruntime.application.commands import ClaimAgentTaskCommand, ClaimReadyAgentTasksCommand
from agentruntime.application.exceptions import (
    AgentTaskNotFoundException,
    AgentTaskVersionConflictException,
    WorkflowInstanceNotFoundException,
    WorkflowNotRunningException,
)
from agentruntime.application.ports_out import AgentTaskRepository, ClockPort, WorkflowInstanceRepository
from agentruntime.application.records import WorkflowInstanceRecord
from agentruntime.application.services.audit import AuditRecorder
from agentruntime.application.telemetry import RuntimeTelemetry
from agentruntime.application.views import AgentTaskView
from agentruntime.domain import agent_task
from agentruntime.domain.enums import AgentTaskState, WorkflowState
from agentruntime.domain.exceptions import (
    AgentTaskAlreadyClaimedException,
    AgentTaskDependencyNotSatisfiedException,
    InvalidAgentTaskTransitionException,
)
from agentruntime.domain.ids import LeaseToken, WorkflowInstanceId

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)

# SPEC-ARO-009: how many extra READY candidates find_claimable_ready_tasks() is asked
# for beyond max_tasks, to give claim_ready() a fallback buffer when some candidates
# lose the optimistic-concurrency race to another concurrent poller — without this, a
# claim_ready() call with no spare candidates simply returns fewer than max_tasks the
# moment its one-and-only candidate turns out to already be claimed, even though the
# role's READY pool may still have plenty of untouched work available right now.
_CANDIDATE_OVER_FETCH_FACTOR = 3


class ClaimAgentTaskService:
    def __init__(
        self, agent_task_repository: AgentTaskRepository, workflow_instance_repository: WorkflowInstanceRepository, clock: ClockPort,
        telemetry: RuntimeTelemetry, audit_recorder: AuditRecorder,
    ) -> None:
        self._agent_task_repository = agent_task_repository
        self._workflow_instance_repository = workflow_instance_repository
        self._clock = clock
        self._telemetry = telemetry
        self._audit_recorder = audit_recorder

    def claim(self, command: ClaimAgentTaskCommand) -> AgentTaskView:
        with tracer.start_as_current_span("task.claim"):
            return self._claim_traced(command)

    def _claim_traced(self, command: ClaimAgentTaskCommand) -> AgentTaskView:
        workflow = self._workflow_instance_repository.find_by_id(command.workflow_instance_id)
        if workflow is None:
            raise WorkflowInstanceNotFoundException(command.workflow_instance_id)
        if workflow.state is not WorkflowState.RUNNING:
            raise WorkflowNotRunningException()

        target = self._agent_task_repository.find_by_workflow_instance_id_and_task_key(
            command.workflow_instance_id, command.task_key
        )
        if target is None:
            raise AgentTaskNotFoundException(f"{command.workflow_instance_id}/{command.task_key}")

        all_dependencies_completed = all(
            self._is_dependency_completed(command.workflow_instance_id, dependency_key)
            for dependency_key in target.depends_on_task_keys
        )

        now = self._clock.now()
        lease_token = LeaseToken.new_token()
        lease_expires_at = now + timedelta(seconds=command.lease_seconds)

        event = agent_task.claim(
            target.id, target.workflow_instance_id, target.state, all_dependencies_completed, target.lease_expires_at,
            target.task_version, command.worker_id, lease_token, lease_expires_at, now,
        )

        updated = dataclasses.replace(
            target, state=event.to_state, task_version=event.task_version, worker_id=command.worker_id,
            lease_token=lease_token, lease_expires_at=lease_expires_at, pause_generation=workflow.pause_generation, updated_at=now,
        )
        saved = self._agent_task_repository.save(updated)

        logger.info(
            "action=claim_task status=completed workflow_instance_id=%s ticket_id=%s ticket_cycle_id=%s "
            "agent_task_id=%s worker_id=%s",
            target.workflow_instance_id, workflow.ticket_id, workflow.ticket_cycle_id, target.id, command.worker_id,
        )
        self._telemetry.record_task_claimed()
        self._audit_recorder.record(
            "TASK_TRANSITION", "claim_task", "AgentTask", str(target.id), "SUCCESS",
            workflow_instance_id=target.workflow_instance_id, ticket_id=workflow.ticket_id, actor_type="WORKER",
            actor_id=command.worker_id,
        )
        return AgentTaskView.from_record(saved, workflow_version=workflow.workflow_version)

    def claim_ready(self, command: ClaimReadyAgentTasksCommand) -> list[AgentTaskView]:
        """05-api-contracts "Claim Task": "Worker provides agentRole, workerId, and
        maxTasks. Service returns tasks with leases." 09-concurrency-and-idempotency:
        "Use FOR UPDATE SKIP LOCKED or an equivalent mechanism to prevent multiple
        workers from claiming the same task" — AgentTaskRepository.
        find_claimable_ready_tasks() applies SKIP LOCKED (Postgres) to steer concurrent
        pollers away from rows another transaction is already mid-claim on, but the real,
        unconditional safety guarantee is the same optimistic task_version CAS every
        other write in this codebase relies on ("or an equivalent mechanism"): a
        candidate that loses the race is simply skipped here, not treated as an error —
        a batch claim returning fewer than max_tasks is an ordinary, expected outcome for
        a polling worker, not a partial failure. Requests more candidates than max_tasks
        (_CANDIDATE_OVER_FETCH_FACTOR) so a handful of lost races don't by themselves
        starve this call down to zero when the role's READY pool still has plenty left.
        """
        with tracer.start_as_current_span("task.claim"):
            return self._claim_ready_traced(command)

    def _claim_ready_traced(self, command: ClaimReadyAgentTasksCommand) -> list[AgentTaskView]:
        candidates = self._agent_task_repository.find_claimable_ready_tasks(
            command.agent_role, command.max_tasks * _CANDIDATE_OVER_FETCH_FACTOR
        )
        now = self._clock.now()
        lease_expires_at = now + timedelta(seconds=command.lease_seconds)
        workflow_cache: dict[WorkflowInstanceId, WorkflowInstanceRecord] = {}
        claimed: list[AgentTaskView] = []

        for candidate in candidates:
            if len(claimed) >= command.max_tasks:
                break

            workflow = workflow_cache.get(candidate.workflow_instance_id)
            if workflow is None:
                workflow = self._workflow_instance_repository.find_by_id(candidate.workflow_instance_id)
                if workflow is None:
                    continue
                workflow_cache[candidate.workflow_instance_id] = workflow
            if workflow.state is not WorkflowState.RUNNING:
                continue

            lease_token = LeaseToken.new_token()
            try:
                # all_dependencies_completed=True: find_claimable_ready_tasks() only
                # returns AgentTaskState.READY rows, which by SPEC-ARO-007's own
                # invariant are never materialized until every depends_on task_key is
                # already COMPLETED — and COMPLETED is terminal, so that fact can never
                # become false again. Re-deriving it here (as claim() does for the
                # single-task path, which must also accept a caller-supplied PENDING
                # task) would only cost N extra lookups per candidate for no behavioral
                # difference.
                event = agent_task.claim(
                    candidate.id, candidate.workflow_instance_id, candidate.state, True, candidate.lease_expires_at,
                    candidate.task_version, command.worker_id, lease_token, lease_expires_at, now,
                )
            except (AgentTaskAlreadyClaimedException, AgentTaskDependencyNotSatisfiedException, InvalidAgentTaskTransitionException):
                continue

            updated = dataclasses.replace(
                candidate, state=event.to_state, task_version=event.task_version, worker_id=command.worker_id,
                lease_token=lease_token, lease_expires_at=lease_expires_at, pause_generation=workflow.pause_generation, updated_at=now,
            )
            try:
                saved = self._agent_task_repository.save(updated)
            except AgentTaskVersionConflictException:
                continue  # another worker won this row first
            claimed.append(AgentTaskView.from_record(saved, workflow_version=workflow.workflow_version))
            self._telemetry.record_task_claimed()
            self._audit_recorder.record(
                "TASK_TRANSITION", "claim_task", "AgentTask", str(saved.id), "SUCCESS",
                workflow_instance_id=saved.workflow_instance_id, ticket_id=workflow.ticket_id, actor_type="WORKER",
                actor_id=command.worker_id,
            )

        logger.info(
            "action=claim_ready_tasks status=completed agent_role=%s worker_id=%s requested=%s claimed=%s",
            command.agent_role, command.worker_id, command.max_tasks, len(claimed),
        )
        return claimed

    def _is_dependency_completed(self, workflow_instance_id: WorkflowInstanceId, dependency_key: str) -> bool:
        dependency = self._agent_task_repository.find_by_workflow_instance_id_and_task_key(workflow_instance_id, dependency_key)
        return dependency is not None and dependency.state is AgentTaskState.COMPLETED
