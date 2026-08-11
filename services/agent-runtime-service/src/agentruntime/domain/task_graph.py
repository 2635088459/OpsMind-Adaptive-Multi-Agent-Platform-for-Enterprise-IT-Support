"""TaskNode / TaskGraph / WorkflowDefinition — the static shape a workflow's
Agent Tasks are planned against. 13-package-and-class-design §"Class
Boundaries": "Planner creates task graph but does not call tools" —
a TaskNode only describes what should run and what it depends on.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from agentruntime.domain.enums import JoinPolicy
from agentruntime.domain.ids import DefinitionVersion, WorkflowDefinitionId, WorkflowType


@dataclass(frozen=True, slots=True)
class TaskNode:
    task_key: str
    task_type: str
    depends_on: frozenset[str]
    join_policy: JoinPolicy
    # SPEC-ARO-009 05-api-contracts "Claim Task": "Worker provides agentRole ... Service
    # returns tasks with leases" — the batch/role-based claim this spec adds filters
    # AgentTaskRecord.agent_role against the polling worker's own role. Optional
    # (defaults to unassigned) rather than required: 01-domain-model lists it as a core
    # Agent Task field, but no Planner capability assigns it yet (deferred, same as
    # SPEC-ARO-007 deferred attempt/maxAttempts/inputPayload) — a graph author may still
    # choose not to set it, in which case that task is only reachable through the
    # existing claim-by-task-key path, never the role-based worker pool.
    agent_role: str | None = None

    def __post_init__(self) -> None:
        if not self.task_key or not self.task_key.strip():
            raise ValueError("task_key must not be blank")
        if not self.task_type or not self.task_type.strip():
            raise ValueError("task_type must not be blank")
        if self.task_key in self.depends_on:
            raise ValueError(f"task_key must not depend on itself: {self.task_key}")
        object.__setattr__(self, "depends_on", frozenset(self.depends_on))


@dataclass(frozen=True, slots=True)
class TaskGraph:
    """Immutable directed acyclic graph of TaskNodes produced by planner.plan() for one
    WorkflowDefinition. 13-package-and-class-design §"Class Boundaries": "Coordinator
    decides which task graph nodes are runnable" — TaskGraph only holds the shape;
    runnability is computed by agentruntime.domain.coordinator against live task state,
    never stored here.
    """

    nodes: tuple[TaskNode, ...] = field(default_factory=tuple)

    def __post_init__(self) -> None:
        object.__setattr__(self, "nodes", tuple(self.nodes))
        keys = {node.task_key for node in self.nodes}
        if len(keys) != len(self.nodes):
            raise ValueError("task_key values must be unique within a task graph")
        for node in self.nodes:
            for dependency in node.depends_on:
                if dependency not in keys:
                    raise ValueError(f"unknown depends_on key '{dependency}' referenced by '{node.task_key}'")

    def node_by_key(self) -> dict[str, TaskNode]:
        return {node.task_key: node for node in self.nodes}


@dataclass(frozen=True, slots=True)
class WorkflowDefinition:
    """A frozen, versioned Workflow Definition. 02-business-invariants §"Workflow Instance
    Invariants": "A Workflow Instance must bind to definitionVersion; recovery must not
    silently switch definitions" — once a WorkflowInstance starts against a
    (definition_id, definition_version) pair, RecoverWorkflowService must reload that
    exact pair, never the catalog's current version.
    """

    id: WorkflowDefinitionId
    version: DefinitionVersion
    workflow_type: WorkflowType
    task_graph: TaskGraph
