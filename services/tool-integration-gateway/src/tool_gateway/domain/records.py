"""Shared record shapes used by both ``tool_gateway.ports`` (Protocol method
signatures) and ``tool_gateway.application`` (construction) — kept in the domain
layer, not application, specifically so ``tool_gateway.ports`` can reference them
without depending on ``tool_gateway.application`` (the import-linter "forbidden"
contract in pyproject.toml: "Ports must depend only on domain"). Distinct from
memory-knowledge-service's own convention, where ports_out.py lives *inside*
application and so can import application.records directly — this domain's own
13-package-and-class-design places ``ports/`` as a sibling of ``application/``,
not nested inside it, which makes this split necessary.

08-transaction-and-outbox (deferred detail to SPEC-TG-003) §"Outbox Publisher":
mirrors memory-knowledge-service's own OutboxRecord shape. 02-business-invariants
INV-TG-006: "Audit records are mandatory" for the ten named action classes.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import datetime

from tool_gateway.domain.enums import OutboxStatus


@dataclass(frozen=True, slots=True)
class OutboxRecord:
    outbox_id: uuid.UUID
    aggregate_type: str
    aggregate_id: str
    event_type: str
    event_version: str
    payload: dict
    occurred_at: datetime
    correlation_id: str
    status: OutboxStatus = OutboxStatus.PENDING
    attempts: int = 0
    available_at: datetime | None = None


@dataclass(frozen=True, slots=True)
class AuditRecordEntry:
    """INV-TG-006's ten mandatory audit actions all funnel through this one
    shape: request accepted/rejected, policy decision received, approval
    requested/granted/denied, credential binding resolved, execution
    started/completed/failed/cancelled, result redacted/published, connector
    disabled/enabled.
    """

    audit_id: uuid.UUID
    action: str
    resource_type: str
    resource_id: str
    outcome: str
    actor_id: str
    correlation_id: str
    recorded_at: datetime
    ticket_id: str | None = None
    detail: str | None = None
    # SPEC-TG-027 "Audit Query And Admin Reporting": 07-data-model's own
    # `tool_audit_records` column list names tool_request_id/execution_id/
    # connector_id — real Postgres columns since SPEC-TG-002, but this
    # dataclass never carried them (every write persisted NULL). Optional,
    # populated only at the call sites where the association is genuinely
    # available (execute_tool_request/reconcile_execution) — 12-observability
    # §"Audit Observability" needs them for "failures and credential usage by
    # connector" specifically, which a resource_type/resource_id pair alone
    # cannot answer (a credential_binding_resolved entry's own resource is the
    # execution, not the connector).
    tool_request_id: str | None = None
    execution_id: str | None = None
    connector_id: str | None = None
