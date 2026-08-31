"""05-api-contracts §"管理 API" request/response models."""

from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, Field


class AuditRecordResponse(BaseModel):
    action: str
    resource_type: str
    resource_id: str
    actor: str
    outcome: str
    correlation_id: str | None
    detail: str
    occurred_at: datetime


class GatePolicyResponse(BaseModel):
    gate_policy: str
    dimension_thresholds: dict[str, float]
    critical_case_required: bool
    max_policy_violations: int
    max_forbidden_tool_calls: int
    max_unauthorized_memory_access: int


class UpsertGatePolicyRequest(BaseModel):
    dimension_thresholds: dict[str, float] = Field(default_factory=dict)
    critical_case_required: bool = True
    max_policy_violations: int = 0
    max_forbidden_tool_calls: int = 0
    max_unauthorized_memory_access: int = 0


class GraderResponse(BaseModel):
    name: str
    grader_type: str
    dimension: str
    version: str


class DispatchOutboxEventsRequest(BaseModel):
    batch_size: int = Field(default=50, ge=1, le=500)
    correlation_id: str = Field(min_length=1)


class DispatchReportResponse(BaseModel):
    dispatched: int
    failed: int
    dead_lettered: int


class PoisonEventResponse(BaseModel):
    """SPEC-EI-035 (langsmith-grader-outbox-failure-recovery)."""

    id: str
    event_id: str
    consumer_name: str
    event_type: str
    payload: str
    error_message: str
    occurred_at: datetime
    recorded_at: datetime
