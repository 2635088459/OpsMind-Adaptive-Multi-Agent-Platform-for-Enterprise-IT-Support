"""SPEC-ARO-037 (phase-10 Conversational Intake): the `conversational_intake`
workflow_type's own fixed, internally-owned task_graph template, plus the
task_type constants SPEC-ARO-039/040 create AgentTasks under.

domain-rules: "Only new enum values are added; no existing enum value's meaning or
transition rule changes." workflow_type/task_type are plain strings already (see
agentruntime.domain.ids.WorkflowType/AgentTaskRecord.task_type — neither is a fixed
Python Enum, and neither `workflow_instances.workflow_type` nor `agent_tasks.task_type`
carries a database CHECK constraint), so introducing these two new values requires no
schema migration at all — confirmed against the real Postgres models before writing
this module, not assumed.

The task graph itself is deliberately empty: unlike every other workflow_type, a
conversation has no fixed number of turns to plan a graph over up front. Instead,
SPEC-ARO-039's inline executor creates one ad hoc `process_user_message` AgentTask per
incoming message (and SPEC-ARO-040 one `execute_confirmed_action` AgentTask per
confirm), each with a fresh task_key and no depends_on — bypassing
CoordinateAgentTasksService.materialize_runnable_tasks() entirely, since that
mechanism is built around a static graph resolved once at start, not an open-ended
turn-by-turn one.
"""

from __future__ import annotations

from agentruntime.application.commands import WorkflowDefinitionInput

CONVERSATIONAL_INTAKE_WORKFLOW_TYPE = "conversational_intake"
PROCESS_USER_MESSAGE_TASK_TYPE = "process_user_message"
EXECUTE_CONFIRMED_ACTION_TASK_TYPE = "execute_confirmed_action"

CONVERSATIONAL_INTAKE_DEFINITION_ID = "conversational-intake-v1"
CONVERSATIONAL_INTAKE_DEFINITION_VERSION = 1


def conversational_intake_definition() -> WorkflowDefinitionInput:
    """SPEC-ARO-037 scope: "the fixed task_graph template conversational_intake
    resolves internally ... the caller of POST /api/v1/conversations never supplies
    one." Called by StartConversationService (SPEC-ARO-038) — never by the direct
    /workflows REST command, which still requires its own caller-supplied definition.
    """
    return WorkflowDefinitionInput(
        definition_id=CONVERSATIONAL_INTAKE_DEFINITION_ID,
        definition_version=CONVERSATIONAL_INTAKE_DEFINITION_VERSION,
        workflow_type=CONVERSATIONAL_INTAKE_WORKFLOW_TYPE,
        task_graph=(),
    )
