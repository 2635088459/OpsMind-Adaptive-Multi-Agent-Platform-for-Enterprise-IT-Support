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
    published_at: datetime | None = None
    """Not written by any in-memory/Postgres repository's own append() (always None at
    append time) — round-tripped back from mark_published() purely for admin
    visibility, mirroring agent-runtime-service's own OutboxRecord.published_at field.
    """


@dataclass(frozen=True, slots=True)
class CommandIdempotencyRecord:
    """SPEC-MK-003 09-concurrency-and-idempotency §"Command Idempotency": every
    idempotency-key-carrying command goes through
    memoryknowledge.application.services.idempotency.CommandIdempotencyGuard instead
    of reimplementing this check. Same key + same request_hash: the cached
    response_json is decoded and returned, execute() is never called again. Same key +
    a *different* request_hash: IdempotencyKeyReusedException (09-concurrency-and-
    idempotency's own "same key with different request hash must return conflict").
    Mirrors agent-runtime-service's own CommandIdempotencyRecord shape exactly.
    """

    idempotency_key: IdempotencyKey
    command_type: str
    target_id: str | None
    request_hash: str
    response_json: str
    created_at: datetime
    expires_at: datetime | None = None


@dataclass(frozen=True, slots=True)
class AuditRecordEntry:
    """SPEC-MK-003 12-observability §"Audit Events": "Audit event 不替代 business event.
    Audit 用于 who/when/why." §"审计动作": ingest_document, approve_candidate,
    reject_candidate, publish_memory, supersede_memory (reserved — no supersede flow
    exists yet, phase-04), delete_memory. `resource_type`/`resource_id` is a generic
    pair (mirrors agent-runtime-service's own AuditRecordEntry) since Memory Knowledge
    audits several different aggregate kinds (MEMORY_CANDIDATE, MEMORY,
    KNOWLEDGE_DOCUMENT), unlike a single-aggregate domain.

    Recording never raises (see AuditRecorder's own docstring) — a failure to append
    an audit row must not fail the primary operation it is describing.
    """

    id: uuid.UUID
    audit_type: str
    action: str
    resource_type: str
    resource_id: str
    ticket_id: str | None
    actor_type: str
    actor_id: str | None
    outcome: str
    correlation_id: str | None
    causation_id: str | None
    detail: str
    occurred_at: datetime


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
