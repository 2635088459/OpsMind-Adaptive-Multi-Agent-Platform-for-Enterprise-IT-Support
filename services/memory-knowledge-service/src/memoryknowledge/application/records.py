"""Persistence-facing records for concerns that are not domain aggregates in their own
right — mirrors agent-runtime-service's own application.records module, which keeps
OutboxRecord/CommandIdempotencyRecord here rather than in domain.* for the same reason:
neither has business-rule methods, only a status a repository transitions through.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import datetime

from memoryknowledge.domain.enums import OutboxStatus
from memoryknowledge.domain.ids import CausationId, CorrelationId, IdempotencyKey


@dataclass(frozen=True, slots=True)
class OutboxRecord:
    """08-transaction-and-outbox (deferred detail to SPEC-MK-003) §"Outbox Publisher".
    SPEC-MK-001 domain-rules: "所有发布事件必须通过 Memory outbox."
    """

    outbox_id: uuid.UUID
    event_type: str
    schema_version: int
    aggregate_id: str
    payload: str
    occurred_at: datetime
    correlation_id: CorrelationId
    causation_id: CausationId
    status: OutboxStatus = OutboxStatus.PENDING
    attempts: int = 0
    available_at: datetime | None = None
    ticket_id: str | None = None


@dataclass(frozen=True, slots=True)
class CommandIdempotencyRecord:
    """09-concurrency-and-idempotency §"Command Idempotency" (deferred detail to
    SPEC-MK-003): a durable-write command that carries an IdempotencyKey checks this
    before applying, and records its own outcome ref here afterward so a retried
    delivery under the same key replays the prior result instead of re-executing.
    """

    idempotency_key: IdempotencyKey
    command_type: str
    result_ref: str
    created_at: datetime


@dataclass(frozen=True, slots=True)
class TicketSnapshot:
    """02-business-invariants §"状态所有权": a read-only view of Ticket state Memory
    Knowledge may reference to judge source trust — never a write path back to
    02-ticket-workflow.
    """

    ticket_id: str
    status: str
    resolved: bool


@dataclass(frozen=True, slots=True)
class WorkflowTrace:
    """02-business-invariants §"状态所有权": a read-only view of Agent Runtime automation
    trace/evidence — never a write path back to 03-agent-runtime-orchestration.
    """

    workflow_instance_id: str
    task_summaries: tuple[str, ...]
    tool_evidence_refs: tuple[str, ...]
