"""13-package-and-class-design §"Application Layer": UpdateWorkingMemoryService, the
sole implementation of UpdateWorkingMemoryUseCase.
"""

from __future__ import annotations

from memoryknowledge.application.commands import UpdateWorkingMemoryCommand
from memoryknowledge.application.ports_out import ClockPort, WorkingMemoryRepository
from memoryknowledge.application.views import WorkingMemoryView
from memoryknowledge.domain.exceptions import WorkingMemoryVersionConflictException
from memoryknowledge.domain.working_memory import RejectedHypothesis, ToolEvidenceRef, WorkingMemory, derive_working_memory_id


class UpdateWorkingMemoryService:
    def __init__(self, working_memory_repository: WorkingMemoryRepository, clock: ClockPort) -> None:
        self._working_memory_repository = working_memory_repository
        self._clock = clock

    def update_working_memory(self, command: UpdateWorkingMemoryCommand) -> WorkingMemoryView:
        now = self._clock.now()
        existing = self._working_memory_repository.find_active_by_scope(
            command.ticket_id, command.ticket_cycle_id, command.workflow_instance_id
        )
        if existing is None:
            if command.expected_version != 0:
                raise WorkingMemoryVersionConflictException(command.expected_version, 0)
            working_memory = WorkingMemory.create(
                derive_working_memory_id(command.ticket_id, command.ticket_cycle_id, command.workflow_instance_id),
                command.ticket_id, command.ticket_cycle_id, command.workflow_instance_id, command.updated_by, now,
            )
        else:
            working_memory = existing

        rejected = tuple(
            RejectedHypothesis(hypothesis=h.hypothesis, reason=h.reason, rejected_at=now) for h in command.reject_hypotheses
        )
        tool_evidence = tuple(
            ToolEvidenceRef(tool_request_id=t.tool_request_id, summary=t.summary, status=t.status, evidence_hash=t.evidence_hash)
            for t in command.add_tool_evidence_refs
        )
        updated = working_memory.apply_update(
            expected_version=command.expected_version if existing is not None else working_memory.version,
            updated_by=command.updated_by, updated_at=now,
            add_facts=command.add_facts, add_hypotheses=command.add_hypotheses, reject_hypotheses=rejected,
            complete_tasks=command.complete_tasks, add_pending_tasks=command.add_pending_tasks,
            add_tool_evidence_refs=tool_evidence, add_approval_decision_refs=command.add_approval_decision_refs,
            context_summary=command.context_summary,
        )
        saved = self._working_memory_repository.save(updated)
        return WorkingMemoryView.from_domain(saved)
