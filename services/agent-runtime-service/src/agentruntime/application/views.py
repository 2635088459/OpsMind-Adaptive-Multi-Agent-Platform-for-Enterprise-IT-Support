"""Application-layer read views returned to the interfaces layer. SPEC-ARO-001
api-contract: "Queries return Runtime state only, not authoritative Ticket state."

to_dict()/from_dict() round-trip each view through plain JSON-friendly primitives —
agentruntime.application.services.idempotency.CommandIdempotencyGuard uses them to
cache and replay a command's response for the "same key, same request" case
(09-concurrency-and-idempotency §"Command Idempotency").
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import datetime

from agentruntime.application.records import (
    AgentTaskRecord,
    CheckpointRecord,
    PoisonEventRecord,
    ToolRequestRecord,
    WorkflowInstanceRecord,
)
from agentruntime.application.redaction import redact_payload
from agentruntime.domain.enums import (
    AgentTaskState,
    CheckpointType,
    ToolRequestStatus,
    WorkflowState,
)
from agentruntime.domain.ids import (
    AgentTaskId,
    CheckpointId,
    DefinitionVersion,
    LeaseToken,
    ToolRequestId,
    WorkflowInstanceId,
)


@dataclass(frozen=True, slots=True)
class WorkflowInstanceView:
    workflow_instance_id: WorkflowInstanceId
    state: WorkflowState
    workflow_version: int
    pause_generation: int
    updated_at: datetime

    @staticmethod
    def from_record(record: WorkflowInstanceRecord) -> WorkflowInstanceView:
        return WorkflowInstanceView(record.id, record.state, record.workflow_version, record.pause_generation, record.updated_at)

    def to_dict(self) -> dict:
        return {
            "workflowInstanceId": str(self.workflow_instance_id), "state": self.state.name,
            "workflowVersion": self.workflow_version, "pauseGeneration": self.pause_generation,
            "updatedAt": self.updated_at.isoformat(),
        }

    @staticmethod
    def from_dict(data: dict) -> WorkflowInstanceView:
        return WorkflowInstanceView(
            WorkflowInstanceId(uuid.UUID(data["workflowInstanceId"])), WorkflowState[data["state"]],
            data["workflowVersion"], data["pauseGeneration"], datetime.fromisoformat(data["updatedAt"]),
        )


@dataclass(frozen=True, slots=True)
class AgentTaskView:
    agent_task_id: AgentTaskId
    workflow_instance_id: WorkflowInstanceId
    task_key: str
    state: AgentTaskState
    task_version: int
    # 09-concurrency-and-idempotency §"Task Claim": "Worker completion must submit
    # claimToken" — returned here so the worker has something to submit back;
    # None once the task is no longer under an outstanding lease (e.g. after complete).
    claim_token: LeaseToken | None
    updated_at: datetime
    # SPEC-ARO-009 05-api-contracts "Claim Task" / 01-domain-model.
    agent_role: str | None = None
    # SPEC-ARO-009 09-concurrency-and-idempotency §"Workflow Version": "Task worker
    # receives workflowVersion when reading a task and must validate it on result
    # submission." Only populated by ClaimAgentTaskService (claim()/claim_ready()) and
    # CompleteAgentTaskService, which both already have the owning Workflow Instance
    # loaded — AgentTaskView.from_record() alone leaves it None (a plain query, e.g.
    # SPEC-ARO-006's AgentTaskQueryService, has no reason to pay for that extra lookup).
    workflow_version: int | None = None

    @staticmethod
    def from_record(record: AgentTaskRecord, workflow_version: int | None = None) -> AgentTaskView:
        return AgentTaskView(
            record.id, record.workflow_instance_id, record.task_key, record.state, record.task_version,
            record.lease_token, record.updated_at, record.agent_role, workflow_version,
        )

    def to_dict(self) -> dict:
        return {
            "agentTaskId": str(self.agent_task_id), "workflowInstanceId": str(self.workflow_instance_id),
            "taskKey": self.task_key, "state": self.state.name, "taskVersion": self.task_version,
            "claimToken": str(self.claim_token) if self.claim_token else None, "updatedAt": self.updated_at.isoformat(),
            "agentRole": self.agent_role, "workflowVersion": self.workflow_version,
        }

    @staticmethod
    def from_dict(data: dict) -> AgentTaskView:
        return AgentTaskView(
            AgentTaskId(uuid.UUID(data["agentTaskId"])), WorkflowInstanceId(uuid.UUID(data["workflowInstanceId"])),
            data["taskKey"], AgentTaskState[data["state"]], data["taskVersion"],
            LeaseToken(uuid.UUID(data["claimToken"])) if data["claimToken"] else None, datetime.fromisoformat(data["updatedAt"]),
            data.get("agentRole"), data.get("workflowVersion"),
        )


@dataclass(frozen=True, slots=True)
class ConversationView:
    """SPEC-ARO-038 05-api-contracts "POST /api/v1/conversations" response — reshapes
    WorkflowInstanceView to just the two fields domain 09's own contract names
    ({conversationId, startedAt}); conversationId is the same value as
    workflow_instance_id (SPEC-ARO-037: "no parallel ID scheme"), never a distinct
    identity.
    """

    conversation_id: WorkflowInstanceId
    started_at: datetime

    def to_dict(self) -> dict:
        return {"conversationId": str(self.conversation_id), "startedAt": self.started_at.isoformat()}

    @staticmethod
    def from_dict(data: dict) -> ConversationView:
        return ConversationView(WorkflowInstanceId(uuid.UUID(data["conversationId"])), datetime.fromisoformat(data["startedAt"]))


@dataclass(frozen=True, slots=True)
class MessageTurnView:
    """SPEC-ARO-039 05-api-contracts "POST /api/v1/conversations/{conversationId}/
    messages" response — a discriminated union, `kind` naming exactly one of "text" /
    "proposedAction" / "escalation" (matching domain 09's own `05-api-contracts` §2.2
    shape exactly), never a partial or ambiguous mix.
    """

    kind: str
    text: str | None = None
    action_id: str | None = None
    action_summary: str | None = None
    action_risk_level: str | None = None
    ticket_id: str | None = None
    display_id: str | None = None
    reason: str | None = None
    assigned_team: str | None = None

    def to_dict(self) -> dict:
        return {
            "kind": self.kind, "text": self.text, "actionId": self.action_id, "actionSummary": self.action_summary,
            "actionRiskLevel": self.action_risk_level, "ticketId": self.ticket_id, "displayId": self.display_id,
            "reason": self.reason, "assignedTeam": self.assigned_team,
        }

    @staticmethod
    def from_dict(data: dict) -> MessageTurnView:
        return MessageTurnView(
            kind=data["kind"], text=data.get("text"), action_id=data.get("actionId"), action_summary=data.get("actionSummary"),
            action_risk_level=data.get("actionRiskLevel"), ticket_id=data.get("ticketId"), display_id=data.get("displayId"),
            reason=data.get("reason"), assigned_team=data.get("assignedTeam"),
        )


@dataclass(frozen=True, slots=True)
class ActionOutcomeView:
    """SPEC-ARO-040 05-api-contracts: confirm's response is
    `{outcome: "done" | "still-processing" | "awaiting-approval"}`; decline's is
    `{outcome: "declined"}` — one shared shape, since both are a single discriminator
    with no other fields (unlike MessageTurnView's richer per-kind payload).
    """

    outcome: str

    def to_dict(self) -> dict:
        return {"outcome": self.outcome}

    @staticmethod
    def from_dict(data: dict) -> ActionOutcomeView:
        return ActionOutcomeView(data["outcome"])


@dataclass(frozen=True, slots=True)
class ConversationDetailView:
    """SPEC-ARO-042 05-api-contracts "GET /api/v1/conversations/{conversationId}" and
    the "most recent conversation" query — a conversation-shaped read over
    WorkflowInstanceRecord. Deliberately does not reconstruct a full message
    transcript: an AgentTaskRecord carries no durable copy of its own input text
    (SPEC-ARO-007's own deferred inputPayload), and a CheckpointRecord is not
    correlated to a specific AgentTaskId — reconstructing "the user said X, the agent
    replied Y" pairs precisely is a real, flagged gap, not silently assumed solved
    here. Read-only; never round-tripped through CommandIdempotencyGuard (mirrors
    CheckpointView's own reasoning).
    """

    conversation_id: WorkflowInstanceId
    state: WorkflowState
    started_at: datetime
    updated_at: datetime

    @staticmethod
    def from_record(record: WorkflowInstanceRecord) -> ConversationDetailView:
        return ConversationDetailView(record.id, record.state, record.created_at, record.updated_at)


@dataclass(frozen=True, slots=True)
class CheckpointView:
    """SPEC-ARO-006 05-api-contracts "GET /workflows/{workflowInstanceId}/checkpoints/
    latest". Read-only — unlike WorkflowInstanceView/AgentTaskView/ToolRequestView, never
    round-tripped through CommandIdempotencyGuard (no command produces this view), so no
    to_dict()/from_dict() pair.

    SPEC-ARO-033 11-security §"Data Protection": payload is redact_payload()'d in
    from_record() — see that function's own docstring for the full rule and for why
    checksum, computed over the real CheckpointRecord.payload, deliberately no longer
    verifies against this view's own (possibly redacted) payload.
    """

    checkpoint_id: CheckpointId
    workflow_instance_id: WorkflowInstanceId
    type: CheckpointType
    schema_version: int
    payload: str
    recorded_at: datetime
    workflow_version: int
    checksum: str
    cursor: str | None

    @staticmethod
    def from_record(record: CheckpointRecord) -> CheckpointView:
        return CheckpointView(
            record.id, record.workflow_instance_id, record.type, record.schema_version, redact_payload(record.payload),
            record.recorded_at, record.workflow_version, record.checksum, record.cursor,
        )


@dataclass(frozen=True, slots=True)
class ToolRequestView:
    """capability is echoed back from SPEC-ARO-017's own new command input — the other
    four Tool Request fields that spec also wired up (gateway_correlation_id/
    policy_snapshot/result_payload/idempotency_key) are structurally None at the moment
    this view is produced (right after dispatch, before SPEC-ARO-019/020's own logic can
    give them a real value), so exposing them here would just be perpetual noise; extend
    this view once those specs land, the same way SPEC-ARO-006/011 extended
    CheckpointView.
    """

    tool_request_id: ToolRequestId
    status: ToolRequestStatus
    updated_at: datetime
    capability: str | None = None

    @staticmethod
    def from_record(record: ToolRequestRecord) -> ToolRequestView:
        return ToolRequestView(record.id, record.status, record.updated_at, record.capability)

    def to_dict(self) -> dict:
        return {
            "toolRequestId": str(self.tool_request_id), "status": self.status.name, "updatedAt": self.updated_at.isoformat(),
            "capability": self.capability,
        }

    @staticmethod
    def from_dict(data: dict) -> ToolRequestView:
        return ToolRequestView(
            ToolRequestId(uuid.UUID(data["toolRequestId"])), ToolRequestStatus[data["status"]],
            datetime.fromisoformat(data["updatedAt"]), data.get("capability"),
        )


@dataclass(frozen=True, slots=True)
class RecoveryReport:
    """13-package-and-class-design §"Application Layer": produced by RecoverWorkflowService.
    02-business-invariants: "Runtime must recover from checkpoints, leases, cursors, and
    outbox after crash" — recoverable_checkpoint_count / open_lease_count demonstrate the
    instance's recoverability is fully derivable from persisted state.
    """

    workflow_instance_id: WorkflowInstanceId
    state: WorkflowState
    workflow_version: int
    definition_version: DefinitionVersion
    recoverable_checkpoint_count: int
    open_lease_count: int
    recovered_at: datetime


@dataclass(frozen=True, slots=True)
class RecoveryScanReport:
    """SPEC-ARO-028 10-failure-handling §"Runtime 崩溃后怎么恢复" steps 1-2: the batch
    counterpart to RecoveryReport, produced by RecoverWorkflowService.scan_and_recover() —
    one pass over every non-terminal Workflow Instance rather than a single one named by
    id. Mirrors DispatchReport/DispatchToolRequestsReport's own "scanned + outcome count +
    timestamp" shape.
    """

    scanned: int
    checkpoint_inconsistent: int
    scanned_at: datetime


@dataclass(frozen=True, slots=True)
class LeaseRecoveryReport:
    """SPEC-ARO-029 10-failure-handling §"Runtime 崩溃后怎么恢复" step 5: "对 CLAIMED/RUNNING
    且 lease 过期的 task 做 retry 或 stale 标记" — produced by
    RecoverExpiredLeaseTasksService.scan_and_recover(), the Agent Task-scoped counterpart
    to RecoveryScanReport (Workflow Instance-scoped). Mirrors the same "scanned + outcome
    counts + timestamp" shape every other batch scanner report in this module uses.
    """

    scanned: int
    retried: int
    staled: int
    scanned_at: datetime


@dataclass(frozen=True, slots=True)
class DispatchReport:
    """08-transaction-and-outbox §"Outbox Publisher": produced by DispatchOutboxEventsService."""

    scanned: int
    published: int
    failed: int
    dead_lettered: int
    dispatched_at: datetime


@dataclass(frozen=True, slots=True)
class DispatchToolRequestsReport:
    """SPEC-ARO-019 08-transaction-and-outbox §"Tool Request Transaction" step 6: produced
    by DispatchToolRequestsService — the Tool Gateway analogue of DispatchReport.
    """

    scanned: int
    dispatched: int
    dispatched_at: datetime


@dataclass(frozen=True, slots=True)
class PoisonEventView:
    """SPEC-ARO-024 10-failure-handling §"Poison Event" step 4's own visibility surface.
    SPEC-ARO-031 05-api-contracts §"Admin API": "mark poison event quarantined" —
    PoisonEventQueryService.mark_quarantined() is now the one command that produces
    this view; list_poison_events() still just reads.

    SPEC-ARO-033 11-security §"Data Protection": payload is redact_payload()'d in
    from_record() — the more likely real target of that redaction than
    CheckpointView's own, since a poisoned delivery is by definition unparsed/
    unvalidated content that could carry anything.
    """

    id: uuid.UUID
    event_id: str
    consumer_name: str
    event_type: str
    payload: str
    error_message: str
    occurred_at: datetime
    recorded_at: datetime
    quarantined_at: datetime | None = None

    @staticmethod
    def from_record(record: PoisonEventRecord) -> PoisonEventView:
        return PoisonEventView(
            record.id, record.event_id, record.consumer_name, record.event_type, redact_payload(record.payload),
            record.error_message, record.occurred_at, record.recorded_at, record.quarantined_at,
        )
