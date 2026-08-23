"""13-package-and-class-design places one ``adapters/db/repositories.py`` behind
a single ``ports/storage_port.py`` — this module houses every persistence
Protocol the application layer needs (ToolRequest/ToolExecution/ResultEnvelope
repositories, outbox, processed-event dedup, audit, and a clock), mirroring
memory-knowledge-service's own single ``ports_out.py`` housing many repository
Protocols in one file. ``ProcessedEventRepository``/``ClockPort`` are not named
by any 01-domain-model field list — added the same way memory-knowledge-service's
own SPEC-MK-001 added ``CommandIdempotencyRepository``: domain-rules §"Required":
"consumed events must use processed-event deduplication" is a phase-00 mandatory
constraint with no consumer yet (phase-02/06 add the first one), wired ahead of
its first caller; every domain factory needs a clock the same way it needs an id
generator.
"""

from __future__ import annotations

from datetime import datetime
from typing import Protocol

from tool_gateway.domain.connector import ToolConnector
from tool_gateway.domain.credential_binding import CredentialBinding
from tool_gateway.domain.ids import ConnectorId, IdempotencyKey, ResultEnvelopeId, ToolExecutionId, ToolRequestId
from tool_gateway.domain.records import AuditRecordEntry, OutboxRecord
from tool_gateway.domain.result_envelope import ToolResultEnvelope
from tool_gateway.domain.tool_execution import ToolExecution
from tool_gateway.domain.tool_request import ToolRequest


class ClockPort(Protocol):
    def now(self) -> datetime: ...


class ToolRequestRepository(Protocol):
    """01-domain-model §"Aggregate Rules"; the compare-and-swap shape mirrors
    memory-knowledge-service's own WorkingMemoryRepository.save() convention.
    """

    def find_by_id(self, tool_request_id: ToolRequestId) -> ToolRequest | None: ...

    def find_by_idempotency_key(
        self, workflow_instance_id: object | None, agent_task_id: object | None, idempotency_key: IdempotencyKey,
    ) -> ToolRequest | None:
        """04-use-cases UC-TG-001 step 2: "Gateway validates schema, actor,
        capability, and idempotency." 07-data-model `tool_requests` §"Unique key":
        ``(workflow_instance_id, agent_task_id, idempotency_key)`` — an
        idempotency key is scoped to the workflow/task that issued it, not
        global, so the same key string reused by an unrelated caller does not
        collide.
        """
        ...

    def save(self, tool_request: ToolRequest, expected_status: object | None) -> ToolRequest:
        """``expected_status=None`` inserts a brand new row. Otherwise replaces
        an existing one under a compare-and-swap on its current status, raising
        a conflict if the stored status no longer matches.
        """
        ...

    def find_queued(self, now: datetime, limit: int) -> list[ToolRequest]:
        """13-package-and-class-design §"ToolExecutionService": "Handles worker
        claim" — the scheduling worker's claim target. SPEC-TG-016: a request
        re-queued after a retryable failure carries its own ``retry_not_before``
        (backoff) — this must exclude any QUEUED row whose backoff has not yet
        elapsed, not just filter on ``status``.
        """
        ...

    def find_non_terminal_by_workflow_instance(self, workflow_instance_id: object) -> list[ToolRequest]:
        """SPEC-TG-022 06-event-contracts §"workflow.cancelled.v1": "When Runtime
        workflow is cancelled, Gateway attempts to cancel associated pending/
        running Tool Requests" — the query that finds them.
        """
        ...


class ToolExecutionRepository(Protocol):
    def find_by_id(self, execution_id: ToolExecutionId) -> ToolExecution | None: ...

    def find_active_by_tool_request(self, tool_request_id: ToolRequestId) -> ToolExecution | None:
        """01-domain-model §"Aggregate Rules": "only one active attempt at a
        time."
        """
        ...

    def find_attempts(self, tool_request_id: ToolRequestId) -> list[ToolExecution]: ...

    def save(self, execution: ToolExecution, expected_status: object | None) -> ToolExecution: ...

    def find_reconcilable(self, limit: int) -> list[ToolExecution]:
        """04-use-cases UC-TG-005 step 3: the reconciliation worker's scan
        target (TIMED_OUT/PARTIAL_SIDE_EFFECT attempts).
        """
        ...

    def find_lease_expired(self, now: datetime, limit: int) -> list[ToolExecution]:
        """SPEC-TG-010 09-concurrency-and-idempotency §"Worker Concurrent Claim":
        "Other workers may take over after lease expiry." 10-failure-handling
        §"Gateway Crash Recovery": "Scan lease-expired executions." Targets
        CLAIMED/PREPARING/INVOKING attempts whose ``lease_expires_at`` has
        passed — a worker that died mid-attempt without ever transitioning it
        out of these statuses.
        """
        ...


class ResultEnvelopeRepository(Protocol):
    def find_by_id(self, result_envelope_id: ResultEnvelopeId) -> ToolResultEnvelope | None: ...

    def save(self, envelope: ToolResultEnvelope) -> ToolResultEnvelope: ...


class ConnectorRepository(Protocol):
    """Persistence half of connector registration — kept distinct from
    ``ports.connector_port.ConnectorRegistryPort`` (which also resolves the
    runtime ``ConnectorPort`` adapter instance, not just the persisted manifest).
    """

    def find_by_id(self, connector_id: object) -> ToolConnector | None: ...

    def save(self, connector: ToolConnector) -> ToolConnector: ...

    def list_all(self) -> list[ToolConnector]: ...


class ProcessedEventRepository(Protocol):
    """domain-rules §"Required": "consumed events must use processed-event
    deduplication." SPEC-TG-009 wired its first two consumers
    (``application.approve_tool_request.ApproveToolRequestService.
    consume_approval_decision``, ``application.consume_policy_rule_changed``).
    """

    def is_processed(self, event_id: str, consumer_name: str) -> bool: ...

    def mark_processed(self, event_id: str, consumer_name: str, processed_at: datetime) -> None: ...


class OutboxRepository(Protocol):
    """00-implementation-roadmap §"Closure Principles": "Every published event
    must go through Gateway outbox." Real broker wiring deferred to SPEC-TG-003
    (see ``ports.event_bus_port`` module docstring); this port's own durable
    outbox-row lifecycle is independent of which publisher adapter is wired.
    """

    def append(self, record: OutboxRecord) -> None: ...

    def find_dispatchable(self, now: datetime, limit: int) -> list[OutboxRecord]: ...

    def mark_published(self, outbox_id: object, published_at: datetime) -> None: ...

    def mark_failed(self, outbox_id: object, next_available_at: datetime, attempts: int) -> None: ...

    def mark_dead_letter(self, outbox_id: object) -> None: ...

    def find_dead_letter(self, limit: int) -> list[OutboxRecord]:
        """SPEC-TG-028 "Outbox Poison Replay Admin Repair" 10-failure-handling
        §"Poison Request": "outbox publication fails beyond threshold" ->
        dead-letter. Implemented by both adapters since SPEC-TG-002/003 but
        never declared here.
        """
        ...

    def find_by_id(self, outbox_id: object) -> OutboxRecord | None: ...

    def requeue(self, outbox_id: object, available_at: datetime) -> None:
        """SPEC-TG-028: admin repair — moves a DEAD_LETTER row back to PENDING
        with a reset attempt counter (see either adapter's own ``requeue()``
        docstring for why it resets rather than continues).
        """
        ...


class AuditRecordRepository(Protocol):
    """02-business-invariants INV-TG-006: "Audit records are mandatory" for the
    ten named action classes. Append-only.
    """

    def append(self, entry: AuditRecordEntry) -> None: ...

    def find_recent(self, limit: int) -> list[AuditRecordEntry]: ...

    def find_by_ticket_id(self, ticket_id: str, limit: int) -> list[AuditRecordEntry]:
        """SPEC-TG-027 "Audit Query And Admin Reporting" 12-observability
        §"Audit Observability": "all tool executions by ticket."
        """
        ...

    def find_by_actor_id(self, actor_id: str, limit: int) -> list[AuditRecordEntry]:
        """12-observability §"Audit Observability": "tool requests by actor.\""""
        ...

    def find_by_connector_id(self, connector_id: str, limit: int) -> list[AuditRecordEntry]:
        """12-observability §"Audit Observability": "failures and credential
        usage by connector."
        """
        ...


class CredentialBindingRepository(Protocol):
    """SPEC-TG-012 07-data-model `credential_bindings`. Deferred by SPEC-TG-001
    ("no current application service writes to this table" — see
    ``adapters.db.models.CredentialBindingRow``'s own docstring); wired for
    real by ``adapters.credentials.vault_adapter``.
    """

    def find_active(self, connector_id: ConnectorId, scope: str) -> CredentialBinding | None:
        """11-security §"Credential Management": "Connector invocation fetches
        short-lived credentials on demand" — reuses an existing ACTIVE binding
        for the same (connector, scope) rather than minting a new vault_ref on
        every single invocation.
        """
        ...

    def save(self, binding: CredentialBinding) -> CredentialBinding: ...
