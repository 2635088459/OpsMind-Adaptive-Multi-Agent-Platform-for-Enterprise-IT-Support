"""13-package-and-class-design §"Application Layer": UpdateWorkingMemoryService, the
sole implementation of UpdateWorkingMemoryUseCase. Implements 04-use-cases UC-01
("更新 Working Memory") step by step: read current WorkingMemory, check expected
version (no last-write-wins), redact new facts/hypotheses/evidence, merge the patch,
bump version, write an audit log entry (step 7 — SPEC-MK-005's own scope; not in
12-observability's own "审计动作" enumeration, but UC-01 names it explicitly for this
exact use case).

SPEC-MK-004 01-domain-model §"WorkingMemory" 约束: "raw secret、完整凭据、未脱敏工具输出
不能进入正文" — every piece of free text destined for facts/hypotheses/rejected-
hypothesis-reasons/context_summary/tool-evidence-summary is redacted here, before it
ever reaches domain.working_memory.WorkingMemory.apply_update(). Unlike
MemoryCandidate/MemoryVersion, WorkingMemory has no separate redacted_text/
redaction_report field in 01-domain-model's own field list — facts/hypotheses/etc.
themselves *are* the already-redacted content, not a raw-plus-redacted pair.
"""

from __future__ import annotations

from memoryknowledge.application.commands import (
    ArchiveWorkingMemoryCommand,
    DeleteWorkingMemoryCommand,
    QueryWorkingMemoryCommand,
    UpdateWorkingMemoryCommand,
)
from memoryknowledge.application.exceptions import WorkingMemoryNotFoundException
from memoryknowledge.application.ports_out import AuditRecordRepository, ClockPort, RedactionPolicyPort, WorkingMemoryRepository
from memoryknowledge.application.services.audit import AuditRecorder
from memoryknowledge.application.views import WorkingMemoryView
from memoryknowledge.domain.exceptions import WorkingMemoryVersionConflictException
from memoryknowledge.domain.working_memory import RejectedHypothesis, ToolEvidenceRef, WorkingMemory, derive_working_memory_id


class UpdateWorkingMemoryService:
    def __init__(
        self, working_memory_repository: WorkingMemoryRepository, redaction_policy_port: RedactionPolicyPort,
        audit_record_repository: AuditRecordRepository, clock: ClockPort,
    ) -> None:
        self._working_memory_repository = working_memory_repository
        self._redaction_policy_port = redaction_policy_port
        self._clock = clock
        self._audit_recorder = AuditRecorder(audit_record_repository, clock)

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
            RejectedHypothesis(hypothesis=self._redact(h.hypothesis), reason=self._redact(h.reason), rejected_at=now)
            for h in command.reject_hypotheses
        )
        tool_evidence = tuple(
            ToolEvidenceRef(
                tool_request_id=t.tool_request_id, summary=self._redact(t.summary), status=t.status, evidence_hash=t.evidence_hash,
            )
            for t in command.add_tool_evidence_refs
        )
        updated = working_memory.apply_update(
            expected_version=command.expected_version if existing is not None else working_memory.version,
            updated_by=command.updated_by, updated_at=now,
            add_facts=tuple(self._redact(f) for f in command.add_facts),
            add_hypotheses=tuple(self._redact(h) for h in command.add_hypotheses),
            reject_hypotheses=rejected,
            complete_tasks=command.complete_tasks, add_pending_tasks=command.add_pending_tasks,
            add_tool_evidence_refs=tool_evidence, add_approval_decision_refs=command.add_approval_decision_refs,
            context_summary=self._redact(command.context_summary) if command.context_summary is not None else None,
        )
        saved = self._working_memory_repository.save(updated)

        self._audit_recorder.record(
            audit_type="MEMORY", action="update_working_memory", resource_type="WORKING_MEMORY", resource_id=str(saved.working_memory_id),
            outcome="SUCCESS", ticket_id=str(command.ticket_id), actor_id=command.updated_by, correlation_id=str(command.correlation_id),
        )
        return WorkingMemoryView.from_domain(saved)

    def _redact(self, text: str) -> str:
        redacted_text, _report = self._redaction_policy_port.redact(text)
        return redacted_text

    def find_working_memory(self, command: QueryWorkingMemoryCommand) -> WorkingMemoryView:
        """SPEC-MK-006 05-api-contracts: `GET /internal/memory/v1/working-memory/
        {workingMemoryId}`. A plain read — no domain call, no write, no version guard —
        mirrors agent-runtime-service's own WorkflowQueryService pattern.
        """
        record = self._working_memory_repository.find_by_id(command.working_memory_id)
        if record is None:
            raise WorkingMemoryNotFoundException(command.working_memory_id)
        return WorkingMemoryView.from_domain(record)

    def archive(self, command: ArchiveWorkingMemoryCommand) -> WorkingMemoryView:
        """SPEC-MK-006 03-state-machine: "ticket cycle 结束后可 ARCHIVED."."""
        working_memory = self._working_memory_repository.find_by_id(command.working_memory_id)
        if working_memory is None:
            raise WorkingMemoryNotFoundException(command.working_memory_id)

        archived = working_memory.archive(expected_version=command.expected_version, updated_at=self._clock.now())
        saved = self._working_memory_repository.save(archived)

        self._audit_recorder.record(
            audit_type="MEMORY", action="archive_working_memory", resource_type="WORKING_MEMORY", resource_id=str(saved.working_memory_id),
            outcome="SUCCESS", ticket_id=str(saved.ticket_id), actor_id=command.actor_id, correlation_id=str(command.correlation_id),
        )
        return WorkingMemoryView.from_domain(saved)

    def delete(self, command: DeleteWorkingMemoryCommand) -> WorkingMemoryView:
        """SPEC-MK-006 03-state-machine: "deletion request 可把 body 清空并保留 tombstone."."""
        working_memory = self._working_memory_repository.find_by_id(command.working_memory_id)
        if working_memory is None:
            raise WorkingMemoryNotFoundException(command.working_memory_id)

        deleted = working_memory.delete(expected_version=command.expected_version, updated_at=self._clock.now())
        saved = self._working_memory_repository.save(deleted)

        self._audit_recorder.record(
            audit_type="MEMORY", action="delete_working_memory", resource_type="WORKING_MEMORY", resource_id=str(saved.working_memory_id),
            outcome="SUCCESS", ticket_id=str(saved.ticket_id), actor_id=command.actor_id, correlation_id=str(command.correlation_id),
        )
        return WorkingMemoryView.from_domain(saved)
