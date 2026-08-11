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

from agentruntime.application.records import AgentTaskRecord, CheckpointRecord, ToolRequestRecord, WorkflowInstanceRecord
from agentruntime.domain.enums import AgentTaskState, CheckpointType, ToolRequestStatus, WorkflowState
from agentruntime.domain.ids import AgentTaskId, CheckpointId, DefinitionVersion, LeaseToken, ToolRequestId, WorkflowInstanceId


@dataclass(frozen=True, slots=True)
class WorkflowInstanceView:
    workflow_instance_id: WorkflowInstanceId
    state: WorkflowState
    workflow_version: int
    pause_generation: int
    updated_at: datetime

    @staticmethod
    def from_record(record: WorkflowInstanceRecord) -> "WorkflowInstanceView":
        return WorkflowInstanceView(record.id, record.state, record.workflow_version, record.pause_generation, record.updated_at)

    def to_dict(self) -> dict:
        return {
            "workflowInstanceId": str(self.workflow_instance_id), "state": self.state.name,
            "workflowVersion": self.workflow_version, "pauseGeneration": self.pause_generation,
            "updatedAt": self.updated_at.isoformat(),
        }

    @staticmethod
    def from_dict(data: dict) -> "WorkflowInstanceView":
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
    def from_record(record: AgentTaskRecord, workflow_version: int | None = None) -> "AgentTaskView":
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
    def from_dict(data: dict) -> "AgentTaskView":
        return AgentTaskView(
            AgentTaskId(uuid.UUID(data["agentTaskId"])), WorkflowInstanceId(uuid.UUID(data["workflowInstanceId"])),
            data["taskKey"], AgentTaskState[data["state"]], data["taskVersion"],
            LeaseToken(uuid.UUID(data["claimToken"])) if data["claimToken"] else None, datetime.fromisoformat(data["updatedAt"]),
            data.get("agentRole"), data.get("workflowVersion"),
        )


@dataclass(frozen=True, slots=True)
class CheckpointView:
    """SPEC-ARO-006 05-api-contracts "GET /workflows/{workflowInstanceId}/checkpoints/
    latest". Read-only — unlike WorkflowInstanceView/AgentTaskView/ToolRequestView, never
    round-tripped through CommandIdempotencyGuard (no command produces this view), so no
    to_dict()/from_dict() pair.
    """

    checkpoint_id: CheckpointId
    workflow_instance_id: WorkflowInstanceId
    type: CheckpointType
    schema_version: int
    payload: str
    recorded_at: datetime

    @staticmethod
    def from_record(record: CheckpointRecord) -> "CheckpointView":
        return CheckpointView(record.id, record.workflow_instance_id, record.type, record.schema_version, record.payload, record.recorded_at)


@dataclass(frozen=True, slots=True)
class ToolRequestView:
    tool_request_id: ToolRequestId
    status: ToolRequestStatus
    updated_at: datetime

    @staticmethod
    def from_record(record: ToolRequestRecord) -> "ToolRequestView":
        return ToolRequestView(record.id, record.status, record.updated_at)

    def to_dict(self) -> dict:
        return {"toolRequestId": str(self.tool_request_id), "status": self.status.name, "updatedAt": self.updated_at.isoformat()}

    @staticmethod
    def from_dict(data: dict) -> "ToolRequestView":
        return ToolRequestView(ToolRequestId(uuid.UUID(data["toolRequestId"])), ToolRequestStatus[data["status"]], datetime.fromisoformat(data["updatedAt"]))


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
class DispatchReport:
    """08-transaction-and-outbox §"Outbox Publisher": produced by DispatchOutboxEventsService."""

    scanned: int
    published: int
    failed: int
    dead_lettered: int
    dispatched_at: datetime
