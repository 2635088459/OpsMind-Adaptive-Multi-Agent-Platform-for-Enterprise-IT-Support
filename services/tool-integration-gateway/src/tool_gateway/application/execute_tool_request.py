"""13-package-and-class-design §"ToolExecutionService": "Handles worker claim,
attempt creation, connector invocation orchestration, and result finalization."
04-use-cases UC-TG-002 steps 3-6 and UC-TG-004 steps 1-2: claims a QUEUED
ToolRequest, creates one ToolExecution attempt, invokes the resolved connector,
and normalizes/redacts the result into a ToolResultEnvelope.

SPEC-TG-016 "Retry Policy And Retry Scheduling" drives the FAILED-outcome
branch's retry-vs-terminal decision (04-use-cases UC-TG-004 steps 3-4:
"Gateway creates the next attempt based on retry policy... If max attempts are
reached, ToolRequest enters TERMINAL_FAILED") — deferred by SPEC-TG-001 for
exactly this spec to pick up, per that module's own prior deferral note.
SPEC-TG-018 additionally routes every EXECUTING-scoped resolution save through
``cancellation_race.save_resolved_tool_request`` — see that module's own
docstring for the concurrent-cancel race it resolves.
"""

from __future__ import annotations

import uuid
from datetime import timedelta

from opentelemetry import trace

from tool_gateway.application.audit import AuditRecorder
from tool_gateway.application.cancellation_race import save_resolved_tool_request
from tool_gateway.application.commands import ExecuteToolRequestCommand
from tool_gateway.application.exceptions import (
    RawOutputForbiddenException,
    ResultEnvelopeNotFoundException,
    ToolRequestNotFoundException,
)
from tool_gateway.application.outbox_events import (
    build_cancelled_event,
    build_denied_completed_event,
    build_retry_scheduled_event,
    build_success_completed_event,
    build_terminal_failed_completed_event,
)
from tool_gateway.application.redaction_helpers import redact_structured_output
from tool_gateway.application.retry_helpers import compute_retry_not_before, is_retry_allowed
from tool_gateway.application.telemetry import ToolGatewayTelemetry
from tool_gateway.application.views import RawOutputView, ToolRequestView, ToolResultView
from tool_gateway.domain.connector import ToolConnector
from tool_gateway.domain.enums import RedactionStatus, RequestedByType, ResultStatus, SideEffectKind, ToolRequestStatus
from tool_gateway.domain.ids import OperationKey, ResultEnvelopeId, ToolExecutionId, ToolRequestId
from tool_gateway.domain.result_envelope import ToolResultEnvelope
from tool_gateway.domain.tool_execution import ToolExecution
from tool_gateway.domain.tool_request import ToolRequest
from tool_gateway.domain.values import ConnectorInvocationSpec
from tool_gateway.ports.connector_port import ConnectorRegistryPort
from tool_gateway.ports.credential_port import CredentialPort
from tool_gateway.ports.raw_output_port import RawOutputStorePort
from tool_gateway.ports.redaction_port import RedactionPort
from tool_gateway.ports.storage_port import (
    AuditRecordRepository,
    ClockPort,
    OutboxRepository,
    ResultEnvelopeRepository,
    ToolExecutionRepository,
    ToolRequestRepository,
)

_tracer = trace.get_tracer(__name__)


class ExecuteToolRequestService:
    def __init__(
        self, tool_request_repository: ToolRequestRepository, tool_execution_repository: ToolExecutionRepository,
        result_envelope_repository: ResultEnvelopeRepository, connector_registry_port: ConnectorRegistryPort,
        credential_port: CredentialPort, redaction_port: RedactionPort, raw_output_store_port: RawOutputStorePort,
        outbox_repository: OutboxRepository, audit_record_repository: AuditRecordRepository, clock: ClockPort,
        telemetry: ToolGatewayTelemetry,
    ) -> None:
        self._tool_request_repository = tool_request_repository
        self._tool_execution_repository = tool_execution_repository
        self._result_envelope_repository = result_envelope_repository
        self._connector_registry_port = connector_registry_port
        self._credential_port = credential_port
        self._redaction_port = redaction_port
        self._raw_output_store_port = raw_output_store_port
        self._outbox_repository = outbox_repository
        self._clock = clock
        self._audit_recorder = AuditRecorder(audit_record_repository, clock)
        self._telemetry = telemetry

    def execute_tool_request(self, command: ExecuteToolRequestCommand) -> ToolRequestView:
        tool_request_id = ToolRequestId(uuid.UUID(command.tool_request_id))
        tool_request = self._tool_request_repository.find_by_id(tool_request_id)
        if tool_request is None:
            raise ToolRequestNotFoundException(command.tool_request_id)

        # INV-TG-008: use the exact connector version bound at intake
        # (create_tool_request.bind_connector()) on the happy path — a
        # connector upgrade registered between accept and execute must not
        # silently swap in a different schema/version than what was actually
        # validated at submission time. ``_handle_unavailable_connector()``
        # below is the one narrow exception, entered only once the bound
        # connector has genuinely become unavailable.
        connector = (
            self._connector_registry_port.find_by_id(tool_request.resolved_connector_id)
            if tool_request.resolved_connector_id is not None else None
        )
        # 03-state-machine §"Connector Health State Machine": the bound
        # connector could have been disabled/degraded (SPEC-TG-006 PATCH
        # /connectors/{id}/status, or SPEC-TG-019's own automatic health-check
        # worker) between accept and execute; the binding is not a standing
        # permission to execute regardless of current health. ``is_executable()``
        # (SPEC-TG-019) admits an eligible DEGRADED fallback the same way
        # ``ConnectorRegistry.find_by_capability`` does at intake time.
        if connector is None or not connector.is_executable():
            return self._handle_unavailable_connector(tool_request, tool_request_id, connector, command)
        adapter = self._connector_registry_port.get_adapter(connector.connector_id)

        expected_request_status = tool_request.status
        now = self._clock.now()
        tool_request = tool_request.begin_execution(now)
        tool_request = self._tool_request_repository.save(tool_request, expected_status=expected_request_status)

        attempt_number = len(self._tool_execution_repository.find_attempts(tool_request_id)) + 1
        # 09-concurrency-and-idempotency §"Connector Operation Key": "Recommended
        # operationKey format: toolRequestId:attemptNumber:connectorId:capabilityName."
        operation_key = (
            OperationKey(f"{tool_request_id}:{attempt_number}:{connector.connector_id}:{tool_request.capability_name}")
            if connector.side_effect_kind is SideEffectKind.MUTATING else None
        )
        execution = ToolExecution.create(
            execution_id=ToolExecutionId.new_id(), tool_request_id=tool_request_id, attempt_number=attempt_number,
            connector_id=connector.connector_id, connector_version=connector.version,
            side_effect_kind=connector.side_effect_kind, operation_key=operation_key,
        )
        lease_expires_at = now + timedelta(seconds=connector.timeout_policy.invoke_timeout_seconds)
        execution = execution.claim(command.lease_owner, lease_expires_at, now)
        # 09-concurrency-and-idempotency §"Worker Concurrent Claim": "Set
        # lease_owner and lease_expires_at" — persisted immediately, not only
        # once the attempt resolves, so the lease is durably visible to other
        # workers and so ToolResultEnvelope's own execution_id foreign key
        # (07-data-model `tool_results`) has a row to reference once the attempt
        # completes. A real Postgres foreign-key constraint caught this gap —
        # the in-memory adapter has no FK to enforce it, so this bug was
        # invisible to the whole SPEC-TG-001 test suite.
        self._tool_execution_repository.save(execution, expected_status=None)
        self._audit_recorder.record(
            action="execution_started", resource_type="TOOL_EXECUTION", resource_id=str(execution.execution_id),
            outcome="CLAIMED", actor_id=command.lease_owner, correlation_id=command.correlation_id,
            tool_request_id=str(tool_request_id), execution_id=str(execution.execution_id), connector_id=str(connector.connector_id),
        )
        execution = execution.begin_preparing()

        credential_handle = None
        if connector.secret_requirements:
            # INV-TG-004: credential values never leave this method as anything
            # other than a short-lived, vault-reference-only handle.
            credential_handle = self._credential_port.resolve(
                connector.connector_id, connector.secret_requirements, tool_request.risk_snapshot.decision_id if tool_request.risk_snapshot else "",
            )
            self._audit_recorder.record(
                action="credential_binding_resolved", resource_type="TOOL_EXECUTION", resource_id=str(execution.execution_id),
                outcome="RESOLVED", actor_id=command.lease_owner, correlation_id=command.correlation_id,
                tool_request_id=str(tool_request_id), execution_id=str(execution.execution_id), connector_id=str(connector.connector_id),
            )
            self._telemetry.record_credential_access(connector.name, ",".join(sorted(connector.secret_requirements)))

        timeout_at = now + timedelta(seconds=connector.timeout_policy.invoke_timeout_seconds)
        execution = execution.begin_invoking(timeout_at)
        spec = ConnectorInvocationSpec(
            connector_id=str(connector.connector_id), connector_version=connector.version,
            operation_key=str(operation_key) if operation_key else None, input_payload=tool_request.input_payload,
            timeout_seconds=connector.timeout_policy.invoke_timeout_seconds,
            credential_binding_id=credential_handle.credential_binding_id if credential_handle else None,
        )
        adapter.validate_input(spec)
        # 12-observability §"Tracing": "5. connector invocation" — "External
        # connector spans must not record sensitive payloads," so this span
        # carries only the connector/capability identity, never spec.input_payload.
        with _tracer.start_as_current_span(
            "tool_gateway.connector_invoke",
            attributes={"connector.name": connector.name, "capability.name": tool_request.capability_name},
        ):
            outcome = adapter.invoke(spec)
        # 12-observability §"Metrics": "tool_execution_latency_seconds" — a
        # fresh timestamp here (not the ``now`` captured before claim/prepare/
        # credential-resolution) so this reflects the connector call itself,
        # not this whole method's own bookkeeping overhead.
        execution_latency_seconds = (self._clock.now() - execution.started_at).total_seconds()

        if outcome.status is ResultStatus.SUCCESS:
            execution = execution.begin_normalizing()
            # 14-testing-strategy §"Security Tests": "redaction failure prevents
            # publishing raw content" — a redact() call that raises must abort
            # this method entirely (propagate), never fall through to
            # publishing whatever partially-redacted value it produced.
            try:
                redacted_summary, summary_metadata = self._redaction_port.redact(outcome.summary)
                # SPEC-TG-014 11-security §"Output Redaction": redaction applied
                # only to `summary` (SPEC-TG-001's own original scope) left every
                # value inside `structured_output` completely unredacted before it
                # reached a published event or API response — a connector's
                # structured output is untrusted free-form JSON, not just its
                # summary string.
                redacted_structured_output, structured_output_redacted = redact_structured_output(
                    self._redaction_port, outcome.structured_output,
                )
            except Exception:
                self._telemetry.record_redaction_failure()
                raise
            any_redacted = bool(summary_metadata.redacted_fields) or structured_output_redacted
            # SPEC-TG-020 INV-TG-007: "Raw output can be read only through
            # controlled storage references" — the connector's own raw content
            # (unredacted, untrusted) is written to the raw-output store, never
            # to the envelope/event/log itself; only the opaque reference
            # travels any further than this one call.
            raw_output_ref = (
                self._raw_output_store_port.store(execution.execution_id, outcome.raw_output)
                if outcome.raw_output is not None else None
            )
            envelope = ToolResultEnvelope.create(
                result_envelope_id=ResultEnvelopeId.new_id(), execution_id=execution.execution_id, status=ResultStatus.SUCCESS,
                summary=redacted_summary, structured_output=redacted_structured_output, raw_output_ref=raw_output_ref,
                redaction_status=RedactionStatus.REDACTED if any_redacted else RedactionStatus.NOT_REQUIRED,
                # SPEC-TG-024 04-memory-knowledge §"Consumed Events" ("obtain
                # ... tool evidence refs"): evidenceRefs existed on
                # ToolResultEnvelope/tool.completed.v1 since SPEC-TG-001 but
                # nothing ever populated it. The controlled raw-output
                # reference itself (never its content — INV-TG-007) is the one
                # evidence artifact this domain's own connector SDK actually
                # produces today; a richer, connector-declared evidence list
                # is future scope once a real connector adapter exists to
                # produce one.
                evidence_refs=(raw_output_ref,) if raw_output_ref else (),
            )
            saved_envelope = self._result_envelope_repository.save(envelope)
            execution = execution.complete(saved_envelope.result_envelope_id, now)
            self._tool_execution_repository.save(execution, expected_status=None)
            tool_request = tool_request.complete(saved_envelope.result_envelope_id, now)
            tool_request = save_resolved_tool_request(
                self._tool_request_repository, tool_request, now, saved_envelope.result_envelope_id,
            )
            self._audit_recorder.record(
                action="result_published", resource_type="TOOL_EXECUTION", resource_id=str(execution.execution_id),
                outcome=tool_request.status.name, actor_id=command.lease_owner, correlation_id=command.correlation_id,
                tool_request_id=str(tool_request_id), execution_id=str(execution.execution_id), connector_id=str(connector.connector_id),
            )
            # SPEC-TG-018: a concurrent cancel may have won the race
            # (``save_resolved_tool_request`` above) — the side effect still
            # completed, but the fact Runtime/Ticket Workflow must learn is
            # "cancelled", not "succeeded".
            if tool_request.status is ToolRequestStatus.CANCELLED:
                self._outbox_repository.append(build_cancelled_event(tool_request, None, command.correlation_id, now))
            else:
                self._outbox_repository.append(
                    build_success_completed_event(tool_request, execution, saved_envelope, command.correlation_id, now)
                )
            self._telemetry.record_execution_latency(
                execution_latency_seconds, connector.name, tool_request.capability_name, tool_request.status.name,
            )
            self._telemetry.record_request_completed(tool_request.status.name)
        elif outcome.status is ResultStatus.TIMED_OUT:
            execution = execution.time_out()
            self._tool_execution_repository.save(execution, expected_status=None)
            self._audit_recorder.record(
                action="execution_failed", resource_type="TOOL_EXECUTION", resource_id=str(execution.execution_id),
                outcome="TIMED_OUT", actor_id=command.lease_owner, correlation_id=command.correlation_id,
                tool_request_id=str(tool_request_id), execution_id=str(execution.execution_id), connector_id=str(connector.connector_id),
            )
            self._telemetry.record_connector_timeout(connector.name)
            self._telemetry.record_execution_latency(execution_latency_seconds, connector.name, tool_request.capability_name, "TIMED_OUT")
        elif outcome.status is ResultStatus.PARTIAL_SIDE_EFFECT:
            execution = execution.mark_partial_side_effect()
            self._tool_execution_repository.save(execution, expected_status=None)
            self._audit_recorder.record(
                action="execution_failed", resource_type="TOOL_EXECUTION", resource_id=str(execution.execution_id),
                outcome="PARTIAL_SIDE_EFFECT", actor_id=command.lease_owner, correlation_id=command.correlation_id,
                tool_request_id=str(tool_request_id), execution_id=str(execution.execution_id), connector_id=str(connector.connector_id),
            )
            self._telemetry.record_execution_latency(
                execution_latency_seconds, connector.name, tool_request.capability_name, "PARTIAL_SIDE_EFFECT",
            )
        else:
            execution = execution.fail_invoking(error_code=outcome.error_code, retryable=outcome.retryable)
            self._telemetry.record_connector_error(connector.name, outcome.error_code)
            self._telemetry.record_execution_latency(execution_latency_seconds, connector.name, tool_request.capability_name, "FAILED")
            # SPEC-TG-016 UC-TG-004 steps 3-4: "Gateway creates the next attempt
            # based on retry policy. If max attempts are reached, ToolRequest
            # enters TERMINAL_FAILED." ``schedule_retry()`` is purely a
            # bookkeeping marker on THIS attempt (see that method's own
            # docstring) — the actual next ``ToolExecution`` row is created by
            # whichever future ``execute_tool_request`` call claims the request
            # again once its backoff elapses.
            if is_retry_allowed(connector, attempt_number, outcome.retryable):
                execution = execution.schedule_retry()
                self._tool_execution_repository.save(execution, expected_status=None)
                retry_not_before = compute_retry_not_before(connector, now)
                tool_request = tool_request.fail(now).retry(now, retry_not_before=retry_not_before)
                tool_request = save_resolved_tool_request(self._tool_request_repository, tool_request, now)
                self._audit_recorder.record(
                    action="execution_retry_scheduled", resource_type="TOOL_EXECUTION", resource_id=str(execution.execution_id),
                    outcome=tool_request.status.name, actor_id=command.lease_owner, correlation_id=command.correlation_id,
                    detail=outcome.error_code, tool_request_id=str(tool_request_id), execution_id=str(execution.execution_id),
                    connector_id=str(connector.connector_id),
                )
                self._telemetry.record_execution_retry(connector.name, tool_request.capability_name)
                if tool_request.status is ToolRequestStatus.CANCELLED:
                    self._outbox_repository.append(build_cancelled_event(tool_request, None, command.correlation_id, now))
                else:
                    self._outbox_repository.append(
                        build_retry_scheduled_event(tool_request, execution, retry_not_before, command.correlation_id, now)
                    )
            else:
                self._tool_execution_repository.save(execution, expected_status=None)
                tool_request = tool_request.fail(now).terminal_fail(outcome.error_code or "connector execution failed", now)
                tool_request = save_resolved_tool_request(self._tool_request_repository, tool_request, now)
                self._audit_recorder.record(
                    action="execution_failed", resource_type="TOOL_EXECUTION", resource_id=str(execution.execution_id),
                    outcome=tool_request.status.name, actor_id=command.lease_owner, correlation_id=command.correlation_id,
                    detail=outcome.error_code, tool_request_id=str(tool_request_id), execution_id=str(execution.execution_id),
                    connector_id=str(connector.connector_id),
                )
                self._telemetry.record_request_completed(tool_request.status.name)
                if tool_request.status is ToolRequestStatus.CANCELLED:
                    self._outbox_repository.append(build_cancelled_event(tool_request, None, command.correlation_id, now))
                else:
                    self._outbox_repository.append(
                        build_terminal_failed_completed_event(tool_request, execution, command.correlation_id, now)
                    )

        current = self._tool_request_repository.find_by_id(tool_request_id)
        assert current is not None
        return ToolRequestView.from_domain(current)

    def _handle_unavailable_connector(
        self, tool_request: ToolRequest, tool_request_id: ToolRequestId, connector: ToolConnector | None,
        command: ExecuteToolRequestCommand,
    ) -> ToolRequestView:
        """SPEC-TG-030 "Crash Recovery Backpressure Scaling" 10-failure-handling
        §"Connector Crash Or Unavailability": "Queued requests need connector
        reselection or terminal failure... High-risk mutation must not switch
        connectors automatically unless policy allows it." Before this spec,
        the caller raised ``CapabilityNotRegisteredException`` here, which
        ``ExecutionWorker.run_once()`` only logs and swallows — a QUEUED
        request bound to a connector that later went DISABLED/DEPRECATED/
        DEGRADED-ineligible was re-picked up and re-failed identically on
        every single poll forever: no terminal state, no audit trail, no
        published fact, ever.

        No ``PolicyPort`` hook exists anywhere in this domain for "does policy
        allow switching connectors for a mutation" (the same class of
        genuinely ungrounded concept every prior authorization-scoping spec in
        this domain has declined to fake), so a MUTATING capability's bound
        connector going unavailable NEVER reselects — only a READ_ONLY
        connector's own capability is eligible for automatic reselection, and
        only onto another already-``is_executable()`` candidate
        (``ConnectorRegistry.find_by_capability`` — the exact same selection
        rule intake already uses). Everything else terminal-fails via the same
        QUEUED -> EXECUTING -> FAILED -> TERMINAL_FAILED path (and the same
        ``save_resolved_tool_request`` concurrent-cancel race resolution) the
        connector-invocation FAILED branch above already uses — this is simply
        that same terminal outcome reached without ever having a real
        ``ToolExecution`` attempt to point to, mirroring
        ``build_denied_completed_event``'s own "no real execution attempt at
        all" POLICY_DENIED/APPROVAL_DENIED shape.
        """

        now = self._clock.now()
        reselected: ToolConnector | None = None
        if connector is not None and connector.side_effect_kind is SideEffectKind.READ_ONLY:
            candidate = self._connector_registry_port.find_by_capability(tool_request.capability_name)
            if candidate is not None and candidate.connector_id != connector.connector_id:
                reselected = candidate

        if reselected is not None:
            rebound = tool_request.bind_connector(reselected.connector_id, reselected.version, now)
            rebound = self._tool_request_repository.save(rebound, expected_status=rebound.status)
            self._audit_recorder.record(
                action="connector_reselected", resource_type="TOOL_REQUEST", resource_id=str(tool_request_id),
                outcome="REBOUND", actor_id=command.lease_owner, correlation_id=command.correlation_id,
                detail=f"{connector.connector_id if connector is not None else 'none'} -> {reselected.connector_id}",
                tool_request_id=str(tool_request_id), connector_id=str(reselected.connector_id),
            )
            return self.execute_tool_request(command)

        expected_request_status = tool_request.status
        tool_request = tool_request.begin_execution(now)
        tool_request = self._tool_request_repository.save(tool_request, expected_status=expected_request_status)
        tool_request = tool_request.fail(now).terminal_fail("connector unavailable for this capability", now)
        tool_request = save_resolved_tool_request(self._tool_request_repository, tool_request, now)
        self._audit_recorder.record(
            action="execution_failed", resource_type="TOOL_REQUEST", resource_id=str(tool_request_id),
            outcome=tool_request.status.name, actor_id=command.lease_owner, correlation_id=command.correlation_id,
            detail="connector unavailable", tool_request_id=str(tool_request_id),
            connector_id=str(connector.connector_id) if connector is not None else None,
        )
        self._telemetry.record_request_completed(tool_request.status.name)
        if tool_request.status is ToolRequestStatus.CANCELLED:
            self._outbox_repository.append(build_cancelled_event(tool_request, None, command.correlation_id, now))
        else:
            self._outbox_repository.append(build_denied_completed_event(tool_request, "TERMINAL_FAILED", command.correlation_id, now))
        return ToolRequestView.from_domain(tool_request)

    def find_result(self, result_envelope_id: str) -> ToolResultView:
        """05-api-contracts §"Result API": ``GET /tool-results/{resultEnvelopeId}``
        — keyed by the result envelope's own id, not the tool request's (a
        caller reaches this from the ``resultEnvelopeId`` field on a completed
        ``GET /tool-requests/{toolRequestId}`` response).
        """

        envelope = self._result_envelope_repository.find_by_id(ResultEnvelopeId(uuid.UUID(result_envelope_id)))
        if envelope is None:
            raise ResultEnvelopeNotFoundException(result_envelope_id)
        return ToolResultView.from_domain(envelope)

    def find_raw_output(
        self, result_envelope_id: str, requested_by_type: str, requested_by_id: str, reason: str, correlation_id: str,
    ) -> RawOutputView:
        """05-api-contracts §"Result API": ``GET /tool-results/{resultEnvelopeId}
        /raw`` — see ``RawOutputForbiddenException``'s own docstring for the
        HUMAN_OPERATOR-only + mandatory-reason gate this enforces. Every
        attempt — granted or denied — writes an audit record (11-security
        §"Audit": "who requested, what capability, why requested... result
        status").
        """

        envelope = self._result_envelope_repository.find_by_id(ResultEnvelopeId(uuid.UUID(result_envelope_id)))
        if envelope is None:
            raise ResultEnvelopeNotFoundException(result_envelope_id)

        if requested_by_type != RequestedByType.HUMAN_OPERATOR.name or not reason or not reason.strip():
            self._audit_recorder.record(
                action="raw_output_access_denied", resource_type="TOOL_RESULT", resource_id=result_envelope_id,
                outcome="FORBIDDEN", actor_id=requested_by_id, correlation_id=correlation_id, detail=reason,
            )
            raise RawOutputForbiddenException(result_envelope_id)

        raw_output = (
            self._raw_output_store_port.retrieve(envelope.raw_output_ref) if envelope.raw_output_ref is not None else None
        )
        self._audit_recorder.record(
            action="raw_output_accessed", resource_type="TOOL_RESULT", resource_id=result_envelope_id,
            outcome="GRANTED", actor_id=requested_by_id, correlation_id=correlation_id, detail=reason,
        )
        return RawOutputView(result_envelope_id=result_envelope_id, raw_output=raw_output)
