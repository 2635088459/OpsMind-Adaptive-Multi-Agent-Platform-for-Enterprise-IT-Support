"""SPEC-ARO-008 04-use-cases UC-02 step 6: "Coordinator unlocks downstream Agent Tasks
when dependencies are satisfied." There is no 07-data-model Workflow Definition catalog
table (StartWorkflowCommand's own docstring: "the Workflow Definition catalog is out of
scope"), so the only durable record of a started instance's task graph shape is the
STARTED checkpoint StartWorkflowService writes (SPEC-ARO-005) — 01-domain-model §"How
Checkpoints Are Stored": "payloadJson must be structured JSON containing recoverable
context: planner output ...". This module is the single place that JSON shape is
defined, shared by the writer (StartWorkflowService, encoding the bound
WorkflowDefinition into the STARTED checkpoint) and the reader
(CoordinateAgentTasksService.unlock_downstream_tasks, decoding it back to a TaskGraph
after a later task completes) so the two never drift apart.
"""

from __future__ import annotations

import json

from agentruntime.domain.enums import JoinPolicy
from agentruntime.domain.task_graph import TaskGraph, TaskNode, WorkflowDefinition


def encode(definition: WorkflowDefinition) -> str:
    return json.dumps({
        "definitionId": str(definition.id), "definitionVersion": definition.version.value,
        "workflowType": str(definition.workflow_type),
        "taskGraph": [
            {
                "taskKey": node.task_key, "taskType": node.task_type, "dependsOn": sorted(node.depends_on),
                "joinPolicy": node.join_policy.name, "agentRole": node.agent_role,
            }
            for node in definition.task_graph.nodes
        ],
    })


def decode_task_graph(payload: str) -> TaskGraph:
    """Only reconstructs the TaskGraph portion — definitionId/definitionVersion/
    workflowType are already known to any caller that already has the owning
    WorkflowInstanceRecord, so they're ignored here rather than re-parsed.
    """
    data = json.loads(payload)
    nodes = tuple(
        TaskNode(
            node["taskKey"], node["taskType"], frozenset(node["dependsOn"]), JoinPolicy[node["joinPolicy"]],
            # SPEC-ARO-009: .get(), not [...] — payloads encoded before this spec
            # (already-persisted STARTED checkpoints) have no "agentRole" key at all.
            node.get("agentRole"),
        )
        for node in data["taskGraph"]
    )
    return TaskGraph(nodes)
