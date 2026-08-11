from __future__ import annotations

import pytest

from agentruntime.application.services import task_graph_codec
from agentruntime.domain.enums import JoinPolicy
from agentruntime.domain.ids import DefinitionVersion, WorkflowDefinitionId, WorkflowType
from agentruntime.domain.task_graph import TaskGraph, TaskNode, WorkflowDefinition

pytestmark = pytest.mark.unit


def test_encode_then_decode_round_trips_the_task_graph_shape() -> None:
    graph = TaskGraph((
        TaskNode("collect", "collect_diagnostics", frozenset(), JoinPolicy.ALL_SUCCESS),
        TaskNode("remediate", "apply_fix", frozenset({"collect"}), JoinPolicy.ALL_SUCCESS),
    ))
    definition = WorkflowDefinition(WorkflowDefinitionId("triage-v1"), DefinitionVersion(1), WorkflowType("TICKET_TRIAGE"), graph)

    payload = task_graph_codec.encode(definition)
    decoded = task_graph_codec.decode_task_graph(payload)

    assert decoded.node_by_key().keys() == {"collect", "remediate"}
    remediate = decoded.node_by_key()["remediate"]
    assert remediate.depends_on == frozenset({"collect"})
    assert remediate.join_policy is JoinPolicy.ALL_SUCCESS
    assert remediate.task_type == "apply_fix"


def test_decode_ignores_the_definition_metadata_fields() -> None:
    """decode_task_graph only reconstructs the TaskGraph — definitionId/definitionVersion/
    workflowType are the caller's own already-known context, not re-parsed here.
    """
    graph = TaskGraph((TaskNode("collect", "collect_diagnostics", frozenset(), JoinPolicy.ALL_SUCCESS),))
    definition = WorkflowDefinition(WorkflowDefinitionId("triage-v1"), DefinitionVersion(1), WorkflowType("TICKET_TRIAGE"), graph)

    decoded = task_graph_codec.decode_task_graph(task_graph_codec.encode(definition))

    assert [node.task_key for node in decoded.nodes] == ["collect"]


def test_encode_then_decode_round_trips_agent_role() -> None:
    """SPEC-ARO-009: a node materialized later via CoordinateAgentTasksService.
    unlock_downstream_tasks() (SPEC-ARO-008) must not silently lose its agent_role —
    that field is decoded straight from this same STARTED-checkpoint payload.
    """
    graph = TaskGraph((TaskNode("collect", "collect_diagnostics", frozenset(), JoinPolicy.ALL_SUCCESS, "triage_agent"),))
    definition = WorkflowDefinition(WorkflowDefinitionId("triage-v1"), DefinitionVersion(1), WorkflowType("TICKET_TRIAGE"), graph)

    decoded = task_graph_codec.decode_task_graph(task_graph_codec.encode(definition))

    assert decoded.node_by_key()["collect"].agent_role == "triage_agent"


def test_decode_defaults_agent_role_to_none_for_a_pre_spec_aro_009_payload() -> None:
    """A STARTED checkpoint written before this spec has no "agentRole" key at all."""
    payload = '{"definitionId": "triage-v1", "definitionVersion": 1, "workflowType": "TICKET_TRIAGE", "taskGraph": [{"taskKey": "collect", "taskType": "collect_diagnostics", "dependsOn": [], "joinPolicy": "ALL_SUCCESS"}]}'

    decoded = task_graph_codec.decode_task_graph(payload)

    assert decoded.node_by_key()["collect"].agent_role is None
