"""SPEC-TG-002/SPEC-TG-003 acceptance-criteria: direct repository-level coverage
against a real, migrated Postgres schema for behaviors the HTTP-level
test_app_postgres_integration.py doesn't reach on its own — the ToolRequest CAS
conflict (the SPEC-ARO-003 lesson this whole platform applies), and the
processed-event dedup + outbox dead-letter mechanisms domain-rules requires but
no application service calls yet in this domain's own scope (see
ports.storage_port.ProcessedEventRepository's own module docstring).
"""

from __future__ import annotations

import dataclasses
import uuid
from datetime import UTC, datetime, timedelta

import pytest

from tool_gateway.adapters.db.postgres_repositories import (
    PostgresAuditRecordRepository,
    PostgresCredentialBindingRepository,
    PostgresOutboxRepository,
    PostgresProcessedEventRepository,
    PostgresToolExecutionRepository,
    PostgresToolRequestRepository,
)
from tool_gateway.application.exceptions import ToolRequestStatusConflictException
from tool_gateway.domain.credential_binding import CredentialBinding
from tool_gateway.domain.enums import RequestedByType, RiskLevel, SideEffectKind, ToolExecutionStatus, ToolRequestStatus
from tool_gateway.domain.ids import ConnectorId, CredentialBindingId, IdempotencyKey, ToolExecutionId, ToolRequestId, WorkflowInstanceId
from tool_gateway.domain.records import AuditRecordEntry, OutboxRecord
from tool_gateway.domain.tool_execution import ToolExecution
from tool_gateway.domain.tool_request import ToolRequest
from tool_gateway.domain.values import RiskDecisionRef

pytestmark = pytest.mark.integration


def _submit(now: datetime) -> ToolRequest:
    return ToolRequest.submit(
        tool_request_id=ToolRequestId.new_id(), idempotency_key=IdempotencyKey(f"idem-{uuid.uuid4()}"), payload_hash="hash-1",
        requested_by_type=RequestedByType.AGENT, requested_by_id="agent-1", capability_name="kubernetes.getPodLogs",
        input_payload={}, reason="investigate", submitted_at=now,
    )


def _risk(now: datetime) -> RiskDecisionRef:
    return RiskDecisionRef(decision_id="d-1", risk_level=RiskLevel.LOW, requires_approval=False, decided_at=now, decided_by="policy")


def test_tool_request_cas_conflict_against_real_postgres(session_factory) -> None:
    """The SPEC-ARO-003 lesson: two callers racing to advance the same row from
    the same expected_status must not both "win" — the second save() must raise,
    not silently overwrite the first.
    """

    repository = PostgresToolRequestRepository(session_factory)
    now = datetime.now(UTC)
    tool_request = _submit(now)
    repository.save(tool_request, expected_status=None)

    validating = tool_request.begin_validation(now)
    repository.save(validating, expected_status=ToolRequestStatus.RECEIVED)

    # A second save() still claiming the OLD expected_status (RECEIVED) must
    # fail — the row has already moved on to VALIDATING.
    with pytest.raises(ToolRequestStatusConflictException):
        repository.save(validating, expected_status=ToolRequestStatus.RECEIVED)

    reloaded = repository.find_by_id(tool_request.tool_request_id)
    assert reloaded is not None
    assert reloaded.status is ToolRequestStatus.VALIDATING


def test_tool_request_idempotency_lookup_scoped_to_workflow_and_task_against_real_postgres(session_factory) -> None:
    """07-data-model `tool_requests` §"Unique key":
    (workflow_instance_id, agent_task_id, idempotency_key) — the same key
    string under a different (workflow, task) scope is a different request.
    """

    repository = PostgresToolRequestRepository(session_factory)
    now = datetime.now(UTC)
    shared_key = IdempotencyKey("shared-key")

    first = ToolRequest.submit(
        tool_request_id=ToolRequestId.new_id(), idempotency_key=shared_key, payload_hash="hash-1",
        requested_by_type=RequestedByType.AGENT, requested_by_id="agent-1", capability_name="kubernetes.getPodLogs",
        input_payload={}, reason="investigate", submitted_at=now,
    )
    repository.save(first, expected_status=None)

    # No workflow/task scope on either side -> same row found.
    found = repository.find_by_idempotency_key(None, None, shared_key)
    assert found is not None and found.tool_request_id == first.tool_request_id

    # A different (unscoped) workflow_instance_id/agent_task_id combination
    # under the same key string must NOT collide with the first.
    not_found = repository.find_by_idempotency_key(str(uuid.uuid4()), str(uuid.uuid4()), shared_key)
    assert not_found is None


def test_processed_event_dedup_against_real_postgres(session_factory) -> None:
    """08-transaction-and-outbox §"Processed Events": "Unique-key conflict means
    skip the event, guaranteeing duplicate event idempotency."
    """

    repository = PostgresProcessedEventRepository(session_factory)
    event_id, consumer_name = f"evt-{uuid.uuid4()}", "approval-decision-consumer"

    assert repository.is_processed(event_id, consumer_name) is False
    repository.mark_processed(event_id, consumer_name, datetime.now(UTC))
    assert repository.is_processed(event_id, consumer_name) is True

    # A second mark_processed() for the same (event_id, consumer_name) is a
    # silent no-op, not an error.
    repository.mark_processed(event_id, consumer_name, datetime.now(UTC))

    # A different consumer_name for the same event_id is a distinct row.
    assert repository.is_processed(event_id, "a-different-consumer") is False


def test_outbox_lifecycle_against_real_postgres(session_factory) -> None:
    """13-package-and-class-design §"OutboxPublisher": pending -> dispatchable
    -> published, and pending -> failed (with backoff) -> dead-letter.
    """

    repository = PostgresOutboxRepository(session_factory)
    now = datetime.now(UTC)

    published_record = OutboxRecord(
        outbox_id=uuid.uuid4(), aggregate_type="TOOL_REQUEST", aggregate_id=str(uuid.uuid4()),
        event_type="tool.request.accepted.v1", event_version="1.0", payload={"capabilityName": "kubernetes.getPodLogs"},
        occurred_at=now, correlation_id=str(uuid.uuid4()),
    )
    repository.append(published_record)
    assert any(r.outbox_id == published_record.outbox_id for r in repository.find_dispatchable(now, 10))

    repository.mark_published(published_record.outbox_id, now)
    assert not any(r.outbox_id == published_record.outbox_id for r in repository.find_dispatchable(now, 10))

    failing_record = OutboxRecord(
        outbox_id=uuid.uuid4(), aggregate_type="TOOL_REQUEST", aggregate_id=str(uuid.uuid4()),
        event_type="tool.completed.v1", event_version="1.0", payload={}, occurred_at=now, correlation_id=str(uuid.uuid4()),
    )
    repository.append(failing_record)
    repository.mark_failed(failing_record.outbox_id, now, attempts=1)
    assert any(r.outbox_id == failing_record.outbox_id for r in repository.find_dispatchable(now, 10))

    repository.mark_dead_letter(failing_record.outbox_id)
    assert not any(r.outbox_id == failing_record.outbox_id for r in repository.find_dispatchable(now, 10))
    assert any(r.outbox_id == failing_record.outbox_id for r in repository.find_dead_letter(10))


def _claimed_execution(tool_request_id: ToolRequestId, lease_expires_at: datetime, attempt_number: int = 1) -> ToolExecution:
    now = lease_expires_at - timedelta(seconds=60)
    return ToolExecution.create(
        execution_id=ToolExecutionId.new_id(), tool_request_id=tool_request_id, attempt_number=attempt_number,
        connector_id=ConnectorId.new_id(), connector_version="1.0.0", side_effect_kind=SideEffectKind.READ_ONLY,
        operation_key=None,
    ).claim("worker-1", lease_expires_at, now)


def test_find_lease_expired_against_real_postgres(session_factory) -> None:
    """SPEC-TG-010 09-concurrency-and-idempotency §"Worker Concurrent Claim":
    the real query must find only in-flight attempts whose lease has actually
    expired, scoped by the ``status IN (...) AND lease_expires_at < now``
    predicate — never a COMPLETED attempt, never one still holding a live
    lease.
    """

    tool_request_repository = PostgresToolRequestRepository(session_factory)
    execution_repository = PostgresToolExecutionRepository(session_factory)
    now = datetime.now(UTC)

    tool_request = _submit(now)
    tool_request_repository.save(tool_request, expected_status=None)

    expired = _claimed_execution(tool_request.tool_request_id, now - timedelta(seconds=30), attempt_number=1)
    execution_repository.save(expired, expected_status=None)

    still_live = _claimed_execution(tool_request.tool_request_id, now + timedelta(minutes=5), attempt_number=2)
    execution_repository.save(still_live, expected_status=None)

    # Test-only shortcut to land an attempt directly at COMPLETED without
    # threading every legal state-machine transition — this only needs the
    # row to exist with that status, not a domain-valid history.
    completed = dataclasses.replace(
        _claimed_execution(tool_request.tool_request_id, now - timedelta(seconds=30), attempt_number=3),
        status=ToolExecutionStatus.COMPLETED,
    )
    execution_repository.save(completed, expected_status=None)

    found = execution_repository.find_lease_expired(datetime.now(UTC), 10)
    found_ids = {e.execution_id for e in found}
    assert expired.execution_id in found_ids
    assert still_live.execution_id not in found_ids
    assert completed.execution_id not in found_ids


def test_credential_binding_create_and_reuse_against_real_postgres(session_factory) -> None:
    """SPEC-TG-012 07-data-model `credential_bindings`: a binding created for
    a (connector, scope) round-trips through Postgres, and ``find_active``
    scopes strictly by connector + scope + ACTIVE status.
    """

    repository = PostgresCredentialBindingRepository(session_factory)
    connector_id = ConnectorId.new_id()
    now = datetime.now(UTC)

    assert repository.find_active(connector_id, "api-token") is None

    binding = CredentialBinding.create(
        credential_binding_id=CredentialBindingId.new_id(), connector_id=connector_id,
        vault_ref="vault://tool-gateway/test/abc123", scope="api-token", now=now,
    )
    repository.save(binding)

    found = repository.find_active(connector_id, "api-token")
    assert found is not None
    assert found.vault_ref == binding.vault_ref
    assert found.created_at is not None

    used = found.mark_used(now + timedelta(seconds=5))
    repository.save(used)
    reloaded = repository.find_active(connector_id, "api-token")
    assert reloaded is not None
    assert reloaded.last_used_at is not None

    # A different scope under the same connector is a distinct binding.
    assert repository.find_active(connector_id, "a-different-scope") is None

    # A revoked binding is no longer returned by find_active.
    repository.save(reloaded.revoke())
    assert repository.find_active(connector_id, "api-token") is None


def test_find_queued_excludes_requests_still_backing_off_against_real_postgres(session_factory) -> None:
    """SPEC-TG-016 09-concurrency-and-idempotency: a request re-queued after a
    retryable failure carries its own ``retry_not_before`` — real Postgres
    round-trips that column and the ``find_queued`` predicate must honor it.
    """

    repository = PostgresToolRequestRepository(session_factory)
    now = datetime.now(UTC)

    immediately_claimable = _submit(now)
    repository.save(immediately_claimable, expected_status=None)
    queued_now = immediately_claimable.begin_validation(now).begin_policy_check(now).auto_approve(
        _risk(now), now,
    ).enqueue(now)
    repository.save(queued_now, expected_status=ToolRequestStatus.RECEIVED)

    backing_off = _submit(now)
    repository.save(backing_off, expected_status=None)
    queued_backing_off = backing_off.begin_validation(now).begin_policy_check(now).auto_approve(
        _risk(now), now,
    ).enqueue(now).begin_execution(now).fail(now).retry(now, retry_not_before=now + timedelta(minutes=5))
    # Saved in one shot from RECEIVED (the row's actual persisted status —
    # every intermediate transition above happened only in memory).
    repository.save(queued_backing_off, expected_status=ToolRequestStatus.RECEIVED)

    found_ids = {r.tool_request_id for r in repository.find_queued(datetime.now(UTC), 50)}
    assert queued_now.tool_request_id in found_ids
    assert queued_backing_off.tool_request_id not in found_ids

    # Once the backoff elapses, the same row becomes claimable again.
    found_after_backoff = {r.tool_request_id for r in repository.find_queued(now + timedelta(minutes=10), 50)}
    assert queued_backing_off.tool_request_id in found_after_backoff


def test_tool_execution_error_code_and_retryable_round_trip_against_real_postgres(session_factory) -> None:
    """SPEC-TG-016 07-data-model `tool_executions`: ``error_code``/``retryable``
    were columns since SPEC-TG-002 but no domain field ever carried them —
    real Postgres previously silently persisted the column defaults regardless
    of what the connector actually reported.
    """

    tool_request_repository = PostgresToolRequestRepository(session_factory)
    execution_repository = PostgresToolExecutionRepository(session_factory)
    now = datetime.now(UTC)

    tool_request = _submit(now)
    tool_request_repository.save(tool_request, expected_status=None)

    execution = ToolExecution.create(
        execution_id=ToolExecutionId.new_id(), tool_request_id=tool_request.tool_request_id, attempt_number=1,
        connector_id=ConnectorId.new_id(), connector_version="1.0.0", side_effect_kind=SideEffectKind.READ_ONLY,
        operation_key=None,
    ).claim("worker-1", now + timedelta(minutes=5), now).begin_preparing().begin_invoking(now + timedelta(minutes=5))
    failed = execution.fail_invoking(error_code="UPSTREAM_5XX", retryable=True)
    execution_repository.save(failed, expected_status=None)

    reloaded = execution_repository.find_by_id(failed.execution_id)
    assert reloaded is not None
    assert reloaded.error_code == "UPSTREAM_5XX"
    assert reloaded.retryable is True


def test_find_non_terminal_by_workflow_instance_against_real_postgres(session_factory) -> None:
    """SPEC-TG-022 06-event-contracts §"workflow.cancelled.v1": the query
    ``ConsumeWorkflowCancelledService`` uses to find affected Tool Requests —
    scoped to one workflow instance, excluding terminal facts.
    """

    repository = PostgresToolRequestRepository(session_factory)
    now = datetime.now(UTC)
    workflow_instance_id = WorkflowInstanceId(uuid.uuid4())
    other_workflow_instance_id = WorkflowInstanceId(uuid.uuid4())

    non_terminal = dataclasses.replace(_submit(now), workflow_instance_id=workflow_instance_id)
    repository.save(non_terminal, expected_status=None)

    terminal = dataclasses.replace(
        _submit(now), workflow_instance_id=workflow_instance_id, status=ToolRequestStatus.CANCELLED,
    )
    repository.save(terminal, expected_status=None)

    different_workflow = dataclasses.replace(_submit(now), workflow_instance_id=other_workflow_instance_id)
    repository.save(different_workflow, expected_status=None)

    found_ids = {r.tool_request_id for r in repository.find_non_terminal_by_workflow_instance(workflow_instance_id)}
    assert non_terminal.tool_request_id in found_ids
    assert terminal.tool_request_id not in found_ids
    assert different_workflow.tool_request_id not in found_ids


def test_audit_record_tool_request_execution_connector_ids_round_trip_against_real_postgres(session_factory) -> None:
    """SPEC-TG-027 07-data-model `tool_audit_records`: ``tool_request_id``/
    ``execution_id``/``connector_id`` are real columns since SPEC-TG-002 that
    used to be silently written as NULL — see
    ``PostgresAuditRecordRepository``'s own module docstring.
    """

    repository = PostgresAuditRecordRepository(session_factory)
    now = datetime.now(UTC)
    tool_request_id, execution_id, connector_id = str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())

    entry = AuditRecordEntry(
        audit_id=uuid.uuid4(), action="execution_started", resource_type="TOOL_EXECUTION", resource_id=execution_id,
        outcome="STARTED", actor_id="worker-1", correlation_id=str(uuid.uuid4()), recorded_at=now,
        tool_request_id=tool_request_id, execution_id=execution_id, connector_id=connector_id,
    )
    repository.append(entry)

    [reloaded] = repository.find_recent(1)
    assert reloaded.tool_request_id == tool_request_id
    assert reloaded.execution_id == execution_id
    assert reloaded.connector_id == connector_id


def test_audit_record_find_by_ticket_id_against_real_postgres(session_factory) -> None:
    """SPEC-TG-027 12-observability §"Audit Observability": "audit trail by
    ticket ID" — ``ticket_id`` lives inside ``metadata_json``, not a dedicated
    column, so this exercises the real JSONB ``.astext`` comparator against
    Postgres, not just an in-memory list comprehension.
    """

    repository = PostgresAuditRecordRepository(session_factory)
    now = datetime.now(UTC)
    ticket_id = f"TCK-{uuid.uuid4()}"

    matching = AuditRecordEntry(
        audit_id=uuid.uuid4(), action="request_accepted", resource_type="TOOL_REQUEST", resource_id=str(uuid.uuid4()),
        outcome="ACCEPTED", actor_id="agent-1", correlation_id=str(uuid.uuid4()), recorded_at=now, ticket_id=ticket_id,
    )
    repository.append(matching)

    other = AuditRecordEntry(
        audit_id=uuid.uuid4(), action="request_accepted", resource_type="TOOL_REQUEST", resource_id=str(uuid.uuid4()),
        outcome="ACCEPTED", actor_id="agent-1", correlation_id=str(uuid.uuid4()), recorded_at=now, ticket_id=f"TCK-{uuid.uuid4()}",
    )
    repository.append(other)

    found = repository.find_by_ticket_id(ticket_id, 10)
    found_ids = {e.audit_id for e in found}
    assert matching.audit_id in found_ids
    assert other.audit_id not in found_ids


def test_audit_record_find_by_actor_id_and_connector_id_against_real_postgres(session_factory) -> None:
    """SPEC-TG-027 12-observability §"Audit Observability": "audit trail by
    actor" and "failures and credential usage by connector".
    """

    repository = PostgresAuditRecordRepository(session_factory)
    now = datetime.now(UTC)
    actor_id, connector_id = f"agent-{uuid.uuid4()}", str(uuid.uuid4())

    entry = AuditRecordEntry(
        audit_id=uuid.uuid4(), action="credential_binding_resolved", resource_type="TOOL_EXECUTION",
        resource_id=str(uuid.uuid4()), outcome="RESOLVED", actor_id=actor_id, correlation_id=str(uuid.uuid4()),
        recorded_at=now, connector_id=connector_id,
    )
    repository.append(entry)

    other = AuditRecordEntry(
        audit_id=uuid.uuid4(), action="credential_binding_resolved", resource_type="TOOL_EXECUTION",
        resource_id=str(uuid.uuid4()), outcome="RESOLVED", actor_id=f"agent-{uuid.uuid4()}", correlation_id=str(uuid.uuid4()),
        recorded_at=now, connector_id=str(uuid.uuid4()),
    )
    repository.append(other)

    by_actor = {e.audit_id for e in repository.find_by_actor_id(actor_id, 10)}
    assert entry.audit_id in by_actor
    assert other.audit_id not in by_actor

    by_connector = {e.audit_id for e in repository.find_by_connector_id(connector_id, 10)}
    assert entry.audit_id in by_connector
    assert other.audit_id not in by_connector


def test_outbox_find_by_id_and_requeue_against_real_postgres(session_factory) -> None:
    """SPEC-TG-028 "Outbox Poison Replay Admin Repair": ``find_by_id`` for the
    admin lookup and ``requeue`` resetting a DEAD_LETTER row back to
    PENDING with ``attempts`` reset to 0 — see
    ``adapters.db.repositories.InMemoryOutboxRepository.requeue``'s own
    docstring for why the backoff schedule is not continued.
    """

    repository = PostgresOutboxRepository(session_factory)
    now = datetime.now(UTC)

    record = OutboxRecord(
        outbox_id=uuid.uuid4(), aggregate_type="TOOL_REQUEST", aggregate_id=str(uuid.uuid4()),
        event_type="tool.completed.v1", event_version="1.0", payload={}, occurred_at=now, correlation_id=str(uuid.uuid4()),
    )
    repository.append(record)

    assert repository.find_by_id(record.outbox_id) is not None
    assert repository.find_by_id(uuid.uuid4()) is None

    repository.mark_failed(record.outbox_id, now, attempts=1)
    repository.mark_dead_letter(record.outbox_id)
    assert any(r.outbox_id == record.outbox_id for r in repository.find_dead_letter(10))

    requeue_at = now + timedelta(minutes=1)
    repository.requeue(record.outbox_id, requeue_at)

    requeued = repository.find_by_id(record.outbox_id)
    assert requeued is not None
    assert requeued.status.name == "PENDING"
    assert requeued.attempts == 0
    assert not any(r.outbox_id == record.outbox_id for r in repository.find_dead_letter(10))
    assert any(r.outbox_id == record.outbox_id for r in repository.find_dispatchable(requeue_at, 10))
