"""13-package-and-class-design §"adapters/db/repositories.py": in-memory
implementations of every ``tool_gateway.ports.storage_port`` Protocol. Real
PostgreSQL-backed repositories are SPEC-TG-002 scope (schema baseline) — see
``adapters.db.models``'s own module docstring. Every ``save()`` below performs a
single compare-and-swap against ``expected_status`` (the lesson recorded against
agent-runtime-service's own SPEC-ARO-003: a bare read-check-write across two
separate statements does not enforce optimistic concurrency); a real Postgres
repository will replace the in-process lock with a
``WHERE status = :expected_status`` predicate on the UPDATE.
"""

from __future__ import annotations

import threading
from datetime import datetime

from tool_gateway.application.exceptions import ToolRequestStatusConflictException
from tool_gateway.domain.connector import ToolConnector
from tool_gateway.domain.credential_binding import CredentialBinding, CredentialBindingStatus
from tool_gateway.domain.ids import ConnectorId, IdempotencyKey, ResultEnvelopeId, ToolExecutionId, ToolRequestId
from tool_gateway.domain.records import AuditRecordEntry, OutboxRecord
from tool_gateway.domain.result_envelope import ToolResultEnvelope
from tool_gateway.domain.tool_execution import ToolExecution
from tool_gateway.domain.tool_request import ToolRequest


class InMemoryToolRequestRepository:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._by_id: dict[ToolRequestId, ToolRequest] = {}
        # 07-data-model `tool_requests` §"Unique key":
        # (workflow_instance_id, agent_task_id, idempotency_key).
        self._by_idempotency_key: dict[tuple[str | None, str | None, str], ToolRequestId] = {}

    def find_by_id(self, tool_request_id: ToolRequestId) -> ToolRequest | None:
        with self._lock:
            return self._by_id.get(tool_request_id)

    def find_by_idempotency_key(
        self, workflow_instance_id: str | None, agent_task_id: str | None, idempotency_key: IdempotencyKey,
    ) -> ToolRequest | None:
        with self._lock:
            key = (workflow_instance_id, agent_task_id, str(idempotency_key))
            tool_request_id = self._by_idempotency_key.get(key)
            return self._by_id.get(tool_request_id) if tool_request_id is not None else None

    def save(self, tool_request: ToolRequest, expected_status: object | None) -> ToolRequest:
        with self._lock:
            current = self._by_id.get(tool_request.tool_request_id)
            if expected_status is None:
                if current is not None:
                    raise ToolRequestStatusConflictException(tool_request.tool_request_id, expected_status)
            else:
                if current is None or current.status is not expected_status:
                    raise ToolRequestStatusConflictException(tool_request.tool_request_id, expected_status)
            self._by_id[tool_request.tool_request_id] = tool_request
            key = (
                str(tool_request.workflow_instance_id) if tool_request.workflow_instance_id else None,
                str(tool_request.agent_task_id) if tool_request.agent_task_id else None,
                str(tool_request.idempotency_key),
            )
            self._by_idempotency_key[key] = tool_request.tool_request_id
            return tool_request

    def find_queued(self, now: datetime, limit: int) -> list[ToolRequest]:
        with self._lock:
            from tool_gateway.domain.enums import ToolRequestStatus

            return [
                r for r in self._by_id.values()
                if r.status is ToolRequestStatus.QUEUED and (r.retry_not_before is None or r.retry_not_before <= now)
            ][:limit]

    def find_non_terminal_by_workflow_instance(self, workflow_instance_id: object) -> list[ToolRequest]:
        with self._lock:
            return [
                r for r in self._by_id.values()
                if r.workflow_instance_id == workflow_instance_id and not r.status.is_terminal()
            ]


class InMemoryToolExecutionRepository:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._by_id: dict[ToolExecutionId, ToolExecution] = {}

    def find_by_id(self, execution_id: ToolExecutionId) -> ToolExecution | None:
        with self._lock:
            return self._by_id.get(execution_id)

    def find_active_by_tool_request(self, tool_request_id: ToolRequestId) -> ToolExecution | None:
        with self._lock:
            attempts = [e for e in self._by_id.values() if e.tool_request_id == tool_request_id]
            active = [e for e in attempts if not e.status.is_terminal() and e.status.name not in ("RETRY_SCHEDULED",)]
            if not active:
                return None
            return max(active, key=lambda e: e.attempt_number)

    def find_attempts(self, tool_request_id: ToolRequestId) -> list[ToolExecution]:
        with self._lock:
            return sorted(
                (e for e in self._by_id.values() if e.tool_request_id == tool_request_id), key=lambda e: e.attempt_number,
            )

    def save(self, execution: ToolExecution, expected_status: object | None) -> ToolExecution:
        with self._lock:
            self._by_id[execution.execution_id] = execution
            return execution

    def find_reconcilable(self, limit: int) -> list[ToolExecution]:
        with self._lock:
            from tool_gateway.domain.enums import ToolExecutionStatus

            reconcilable = {ToolExecutionStatus.TIMED_OUT, ToolExecutionStatus.PARTIAL_SIDE_EFFECT}
            return [e for e in self._by_id.values() if e.status in reconcilable][:limit]

    def find_lease_expired(self, now: datetime, limit: int) -> list[ToolExecution]:
        with self._lock:
            from tool_gateway.domain.enums import ToolExecutionStatus

            in_flight = {ToolExecutionStatus.CLAIMED, ToolExecutionStatus.PREPARING, ToolExecutionStatus.INVOKING}
            return [
                e for e in self._by_id.values()
                if e.status in in_flight and e.lease_expires_at is not None and e.lease_expires_at < now
            ][:limit]


class InMemoryResultEnvelopeRepository:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._by_id: dict[ResultEnvelopeId, ToolResultEnvelope] = {}

    def find_by_id(self, result_envelope_id: ResultEnvelopeId) -> ToolResultEnvelope | None:
        with self._lock:
            return self._by_id.get(result_envelope_id)

    def save(self, envelope: ToolResultEnvelope) -> ToolResultEnvelope:
        with self._lock:
            self._by_id[envelope.result_envelope_id] = envelope
            return envelope


class InMemoryConnectorRepository:
    """Persistence half of connector registration — the resolve-by-capability
    and adapter-instance lookup live in ``adapters.connectors.registry``
    (``ConnectorRegistryPort``), which composes over this repository.
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._by_id: dict[ConnectorId, ToolConnector] = {}

    def find_by_id(self, connector_id: ConnectorId) -> ToolConnector | None:
        with self._lock:
            return self._by_id.get(connector_id)

    def save(self, connector: ToolConnector) -> ToolConnector:
        with self._lock:
            self._by_id[connector.connector_id] = connector
            return connector

    def list_all(self) -> list[ToolConnector]:
        with self._lock:
            return list(self._by_id.values())


class InMemoryOutboxRepository:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._records: dict[object, OutboxRecord] = {}

    def append(self, record: OutboxRecord) -> None:
        with self._lock:
            self._records[record.outbox_id] = record

    def find_dispatchable(self, now: datetime, limit: int) -> list[OutboxRecord]:
        from tool_gateway.domain.enums import OutboxStatus

        with self._lock:
            return [
                r for r in self._records.values()
                if r.status is OutboxStatus.PENDING and (r.available_at is None or r.available_at <= now)
            ][:limit]

    def mark_published(self, outbox_id: object, published_at: datetime) -> None:
        import dataclasses

        from tool_gateway.domain.enums import OutboxStatus

        with self._lock:
            record = self._records[outbox_id]
            self._records[outbox_id] = dataclasses.replace(record, status=OutboxStatus.PUBLISHED)

    def mark_failed(self, outbox_id: object, next_available_at: datetime, attempts: int) -> None:
        import dataclasses

        with self._lock:
            record = self._records[outbox_id]
            self._records[outbox_id] = dataclasses.replace(record, attempts=attempts, available_at=next_available_at)

    def mark_dead_letter(self, outbox_id: object) -> None:
        import dataclasses

        from tool_gateway.domain.enums import OutboxStatus

        with self._lock:
            record = self._records[outbox_id]
            self._records[outbox_id] = dataclasses.replace(record, status=OutboxStatus.DEAD_LETTER)

    def find_dead_letter(self, limit: int) -> list[OutboxRecord]:
        from tool_gateway.domain.enums import OutboxStatus

        with self._lock:
            return [r for r in self._records.values() if r.status is OutboxStatus.DEAD_LETTER][:limit]

    def find_by_id(self, outbox_id: object) -> OutboxRecord | None:
        with self._lock:
            return self._records.get(outbox_id)

    def requeue(self, outbox_id: object, available_at: datetime) -> None:
        """SPEC-TG-028 "Outbox Poison Replay Admin Repair": moves a
        DEAD_LETTER row back to PENDING with a reset attempt counter —
        deliberately zeroed, not left at the exhausted count, since an admin
        replay is a fresh chance, not a continuation of the same backoff
        schedule that already ran out.
        """

        import dataclasses

        from tool_gateway.domain.enums import OutboxStatus

        with self._lock:
            record = self._records[outbox_id]
            self._records[outbox_id] = dataclasses.replace(
                record, status=OutboxStatus.PENDING, attempts=0, available_at=available_at,
            )


class InMemoryAuditRecordRepository:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._entries: list[AuditRecordEntry] = []

    def append(self, entry: AuditRecordEntry) -> None:
        with self._lock:
            self._entries.append(entry)

    def find_recent(self, limit: int) -> list[AuditRecordEntry]:
        with self._lock:
            return list(reversed(self._entries))[:limit]

    def find_by_ticket_id(self, ticket_id: str, limit: int) -> list[AuditRecordEntry]:
        with self._lock:
            return [e for e in reversed(self._entries) if e.ticket_id == ticket_id][:limit]

    def find_by_actor_id(self, actor_id: str, limit: int) -> list[AuditRecordEntry]:
        with self._lock:
            return [e for e in reversed(self._entries) if e.actor_id == actor_id][:limit]

    def find_by_connector_id(self, connector_id: str, limit: int) -> list[AuditRecordEntry]:
        with self._lock:
            return [e for e in reversed(self._entries) if e.connector_id == connector_id][:limit]


class InMemoryProcessedEventRepository:
    """domain-rules §"Required": "consumed events must use processed-event
    deduplication." Not yet called by any use case in this spec's own scope —
    see ``ports.storage_port.ProcessedEventRepository``'s own module docstring.
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._processed: set[tuple[str, str]] = set()

    def is_processed(self, event_id: str, consumer_name: str) -> bool:
        with self._lock:
            return (event_id, consumer_name) in self._processed

    def mark_processed(self, event_id: str, consumer_name: str, processed_at: datetime) -> None:
        with self._lock:
            self._processed.add((event_id, consumer_name))


class InMemoryCredentialBindingRepository:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._by_id: dict[object, CredentialBinding] = {}

    def find_active(self, connector_id: ConnectorId, scope: str) -> CredentialBinding | None:
        with self._lock:
            candidates = [
                b for b in self._by_id.values()
                if b.connector_id == connector_id and b.scope == scope and b.status is CredentialBindingStatus.ACTIVE
            ]
            return candidates[0] if candidates else None

    def save(self, binding: CredentialBinding) -> CredentialBinding:
        with self._lock:
            self._by_id[binding.credential_binding_id] = binding
            return binding
