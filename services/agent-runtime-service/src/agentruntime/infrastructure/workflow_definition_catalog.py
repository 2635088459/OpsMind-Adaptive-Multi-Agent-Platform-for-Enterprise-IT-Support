"""SPEC-ARO-005 module boundary placeholder for WorkflowDefinitionCatalogPort, mirroring
NoOpTicketSnapshotPort's own honesty-over-fabrication stance: rather than inventing a
category-routing rules engine with no 07-data-model table behind it, this adapter always
resolves to the one canonical "collect diagnostics" TICKET_TRIAGE definition already used
as the fixture definition across every SPEC-ARO-001..004 REST/worker walkthrough, so a
ticket.created-triggered start and a directly-REST-triggered start land on identical,
already-well-tested ground. A later spec (phase-02 agent-task-orchestration) is expected
to replace this with a real authored-definition catalog keyed by category.
"""

from __future__ import annotations

from agentruntime.application.commands import TaskNodeInput, WorkflowDefinitionInput

_DEFAULT_DEFINITION = WorkflowDefinitionInput(
    definition_id="triage-v1",
    definition_version=1,
    workflow_type="TICKET_TRIAGE",
    task_graph=(TaskNodeInput(task_key="collect", task_type="collect_diagnostics", depends_on=frozenset(), join_policy="ALL_SUCCESS"),),
)


class StaticWorkflowDefinitionCatalogAdapter:
    def resolve_for_ticket(self, category: str) -> WorkflowDefinitionInput:
        return _DEFAULT_DEFINITION
