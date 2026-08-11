"""13-package-and-class-design §"Class Boundaries": "Planner creates task graph but does
not call tools." SPEC-ARO-001 fixes the module boundary only: the graph is the one frozen
into the bound WorkflowDefinition (02-business-invariants: "recovery must not silently
switch definitions"). Definition-time and runtime dynamic planning (conditional branches,
generated sub-graphs) is deferred to phase-02 (agent-task-orchestration).
"""

from __future__ import annotations

from agentruntime.domain.task_graph import TaskGraph, WorkflowDefinition


def plan(definition: WorkflowDefinition) -> TaskGraph:
    return definition.task_graph
