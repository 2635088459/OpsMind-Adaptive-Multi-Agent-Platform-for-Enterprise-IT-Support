"""02-business-invariants §"状态所有权": Memory Knowledge may read Agent Runtime
automation trace/evidence but must never write Workflow state. Mirrors
infrastructure.ticket_snapshot's own NoOp-until-a-real-client-exists posture. A real
client is phase-06 (cross-domain-contracts) scope.
"""

from __future__ import annotations

from memoryknowledge.application.records import WorkflowTrace
from memoryknowledge.domain.ids import WorkflowInstanceId


class NoOpWorkflowTracePort:
    def find_trace(self, workflow_instance_id: WorkflowInstanceId) -> WorkflowTrace | None:
        return None
