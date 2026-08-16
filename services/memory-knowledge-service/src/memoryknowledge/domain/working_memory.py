"""01-domain-model §"WorkingMemory": ticket/cycle/workflow-scoped short-term context.
02-business-invariants §"Working Memory 不变量": "Working Memory 是短期状态，不等于长期记忆."
"""

from __future__ import annotations

import dataclasses
import uuid
from dataclasses import dataclass
from datetime import datetime

from memoryknowledge.domain.enums import WorkingMemoryStatus
from memoryknowledge.domain.exceptions import InvalidWorkingMemoryStateException, WorkingMemoryVersionConflictException
from memoryknowledge.domain.ids import TicketCycleId, TicketId, WorkflowInstanceId, WorkingMemoryId

_WORKING_MEMORY_ID_NAMESPACE = uuid.UUID("6f2a1b9e-6c1a-4d2e-8f3a-6b7c9d0e1f22")
"""Fixed namespace for uuid5-deriving a WorkingMemoryId from its scope. 05-api-contracts:
`PATCH /internal/memory/v1/working-memory/{workingMemoryId}` assumes the caller already
knows workingMemoryId — a deterministic id lets Runtime construct it from
ticket/cycle/workflow without a prior create round trip, matching 01-domain-model's own
"scope 必须是 ticketId + ticketCycleId + workflowInstanceId" (the scope IS the identity).
"""


def derive_working_memory_id(ticket_id: TicketId, ticket_cycle_id: TicketCycleId, workflow_instance_id: WorkflowInstanceId) -> WorkingMemoryId:
    name = f"{ticket_id}:{ticket_cycle_id}:{workflow_instance_id}"
    return WorkingMemoryId(uuid.uuid5(_WORKING_MEMORY_ID_NAMESPACE, name))


@dataclass(frozen=True, slots=True)
class ToolEvidenceRef:
    """02-business-invariants: "Tool evidence 只保存引用、摘要、状态和哈希，不保存敏感原始
    输出." No raw tool response field exists on this type by design.
    """

    tool_request_id: str
    summary: str
    status: str
    evidence_hash: str


@dataclass(frozen=True, slots=True)
class RejectedHypothesis:
    """02-business-invariants: "被 reject 的 hypothesis 必须保留原因，避免重复调查."""

    hypothesis: str
    reason: str
    rejected_at: datetime


@dataclass(frozen=True, slots=True)
class WorkingMemory:
    working_memory_id: WorkingMemoryId
    ticket_id: TicketId
    ticket_cycle_id: TicketCycleId
    workflow_instance_id: WorkflowInstanceId
    version: int
    status: WorkingMemoryStatus
    facts: tuple[str, ...]
    hypotheses: tuple[str, ...]
    rejected_hypotheses: tuple[RejectedHypothesis, ...]
    completed_tasks: tuple[str, ...]
    pending_tasks: tuple[str, ...]
    tool_evidence_refs: tuple[ToolEvidenceRef, ...]
    approval_decision_refs: tuple[str, ...]
    context_summary: str
    updated_by: str
    updated_at: datetime

    @staticmethod
    def create(
        working_memory_id: WorkingMemoryId,
        ticket_id: TicketId,
        ticket_cycle_id: TicketCycleId,
        workflow_instance_id: WorkflowInstanceId,
        updated_by: str,
        updated_at: datetime,
    ) -> "WorkingMemory":
        """01-domain-model: "scope 必须是 ticketId + ticketCycleId + workflowInstanceId."
        02-business-invariants: "同一个 scope 只能有一个 active WorkingMemory" — enforced by
        the repository's uniqueness check on that scope key, not here.
        """
        return WorkingMemory(
            working_memory_id=working_memory_id, ticket_id=ticket_id, ticket_cycle_id=ticket_cycle_id,
            workflow_instance_id=workflow_instance_id, version=1, status=WorkingMemoryStatus.ACTIVE,
            facts=(), hypotheses=(), rejected_hypotheses=(), completed_tasks=(), pending_tasks=(),
            tool_evidence_refs=(), approval_decision_refs=(), context_summary="",
            updated_by=updated_by, updated_at=updated_at,
        )

    def apply_update(
        self,
        *,
        expected_version: int,
        updated_by: str,
        updated_at: datetime,
        add_facts: tuple[str, ...] = (),
        add_hypotheses: tuple[str, ...] = (),
        reject_hypotheses: tuple[RejectedHypothesis, ...] = (),
        complete_tasks: tuple[str, ...] = (),
        add_pending_tasks: tuple[str, ...] = (),
        add_tool_evidence_refs: tuple[ToolEvidenceRef, ...] = (),
        add_approval_decision_refs: tuple[str, ...] = (),
        context_summary: str | None = None,
    ) -> "WorkingMemory":
        """01-domain-model: "更新必须使用 optimistic version." Purely additive/merging by
        design (facts/hypotheses accumulate; rejected hypotheses move from hypotheses to
        rejected_hypotheses and keep their reason) — Working Memory never silently drops
        prior context on a partial update.
        """
        if self.status is not WorkingMemoryStatus.ACTIVE:
            raise InvalidWorkingMemoryStateException(self.status)
        if expected_version != self.version:
            raise WorkingMemoryVersionConflictException(expected_version, self.version)

        rejected_texts = {r.hypothesis for r in reject_hypotheses}
        remaining_hypotheses = tuple(h for h in self.hypotheses if h not in rejected_texts)
        new_hypotheses = tuple(h for h in add_hypotheses if h not in rejected_texts)

        return dataclasses.replace(
            self,
            version=self.version + 1,
            facts=self.facts + tuple(f for f in add_facts if f not in self.facts),
            hypotheses=remaining_hypotheses + new_hypotheses,
            rejected_hypotheses=self.rejected_hypotheses + reject_hypotheses,
            completed_tasks=self.completed_tasks + tuple(t for t in complete_tasks if t not in self.completed_tasks),
            pending_tasks=tuple(t for t in self.pending_tasks if t not in complete_tasks)
            + tuple(t for t in add_pending_tasks if t not in self.pending_tasks and t not in complete_tasks),
            tool_evidence_refs=self.tool_evidence_refs + add_tool_evidence_refs,
            approval_decision_refs=self.approval_decision_refs + add_approval_decision_refs,
            context_summary=self.context_summary if context_summary is None else context_summary,
            updated_by=updated_by, updated_at=updated_at,
        )

    def archive(self, updated_at: datetime) -> "WorkingMemory":
        """03-state-machine: "ticket cycle 结束后可 ARCHIVED."."""
        if self.status is not WorkingMemoryStatus.ACTIVE:
            raise InvalidWorkingMemoryStateException(self.status)
        return dataclasses.replace(self, status=WorkingMemoryStatus.ARCHIVED, updated_at=updated_at)

    def delete(self, updated_at: datetime) -> "WorkingMemory":
        """03-state-machine: "deletion request 可把 body 清空并保留 tombstone."."""
        return dataclasses.replace(
            self, status=WorkingMemoryStatus.DELETED, facts=(), hypotheses=(), tool_evidence_refs=(),
            context_summary="", updated_at=updated_at,
        )
