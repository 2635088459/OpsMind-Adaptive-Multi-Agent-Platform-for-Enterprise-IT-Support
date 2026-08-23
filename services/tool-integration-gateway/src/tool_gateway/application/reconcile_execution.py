"""13-package-and-class-design §"Application Layer": ``reconcile_execution.py``.
04-use-cases UC-TG-005 "Partial Side Effect Reconciliation", steps 2-5.
``ToolExecution``'s own state machine only allows RECONCILING -> {COMPLETED,
TERMINAL_FAILED} — there is no third "still pending" execution status — so
every outcome this service reaches, including UNCERTAIN (see below),
terminates the *attempt* at TERMINAL_FAILED; what still differs by outcome is
the *ToolRequest*'s own final published fact.

SPEC-TG-017 "Timeout Partial Side Effect Reconciliation" closed two real gaps
in the failure branch (step 5: "If failure is confirmed and retry is allowed,
a new attempt is created"): it previously always jumped straight to
TERMINAL_FAILED regardless of the connector's own retry policy, and never
published a final ``tool.completed.v1`` when it did — now shares the exact
same retry-vs-terminal decision ``application.retry_helpers`` gives
``execute_tool_request``'s own FAILED branch. SPEC-TG-018 additionally routes
both branches' EXECUTING-scoped saves through
``cancellation_race.save_resolved_tool_request``.

SPEC-TG-032's own final coverage audit against 14-testing-strategy's own
Recovery Tests list ("reconciliation after timeout succeeds/fails/remains
uncertain") found the third outcome was never actually reached: any non-
SUCCESS ``outcome.status`` — including ``ResultStatus.UNCERTAIN``, a real
enum member that existed since SPEC-TG-001 purely for this — fell into the
same branch as a genuinely *confirmed* FAILED outcome, eligible for the exact
same automatic retry decision. 10-failure-handling §"Reconciliation": "If the
result remains UNCERTAIN for too long, Gateway publishes final uncertain
result and marks human handling required" — blindly retrying a MUTATING
capability whose outcome is genuinely unknown risks exactly the double-side-
effect this whole domain otherwise guards against (SPEC-TG-013's operation-key
guard, SPEC-TG-030's "must not switch connectors automatically" refusal). Now
a distinct branch: never retried, always reaches TERMINAL_FAILED with its own
``execution_uncertain_after_reconciliation`` audit action and a
``tool.completed.v1`` whose ``status`` reads ``"UNCERTAIN"`` (not
``"TERMINAL_FAILED"``) — "marks human handling required" surfaces through
that distinguishable status plus the audit trail; no dedicated
persisted "needs human review" field exists anywhere in this domain's own
schema to set instead (the same "narrow scope honestly rather than inventing
ungrounded state" call this domain has made repeatedly, e.g. SPEC-TG-021's
authorization narrowing).
"""

from __future__ import annotations

import uuid

from tool_gateway.application.audit import AuditRecorder
from tool_gateway.application.cancellation_race import save_resolved_tool_request
from tool_gateway.application.commands import ReconcileExecutionCommand
from tool_gateway.application.exceptions import ToolExecutionNotFoundException, ToolRequestNotFoundException
from tool_gateway.application.outbox_events import (
    build_cancelled_event,
    build_retry_scheduled_event,
    build_success_completed_event,
    build_terminal_failed_completed_event,
)
from tool_gateway.application.redaction_helpers import redact_structured_output
from tool_gateway.application.retry_helpers import compute_retry_not_before, is_retry_allowed
from tool_gateway.application.telemetry import ToolGatewayTelemetry
from tool_gateway.application.views import ToolRequestView
from tool_gateway.domain.enums import RedactionStatus, ResultStatus, ToolRequestStatus
from tool_gateway.domain.ids import ResultEnvelopeId, ToolExecutionId
from tool_gateway.domain.result_envelope import ToolResultEnvelope
from tool_gateway.domain.values import ConnectorInvocationSpec
from tool_gateway.ports.connector_port import ConnectorRegistryPort
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


class ReconcileExecutionService:
    def __init__(
        self, tool_execution_repository: ToolExecutionRepository, tool_request_repository: ToolRequestRepository,
        result_envelope_repository: ResultEnvelopeRepository, connector_registry_port: ConnectorRegistryPort,
        redaction_port: RedactionPort, raw_output_store_port: RawOutputStorePort, outbox_repository: OutboxRepository,
        audit_record_repository: AuditRecordRepository, clock: ClockPort, telemetry: ToolGatewayTelemetry,
    ) -> None:
        self._tool_execution_repository = tool_execution_repository
        self._tool_request_repository = tool_request_repository
        self._result_envelope_repository = result_envelope_repository
        self._connector_registry_port = connector_registry_port
        self._redaction_port = redaction_port
        self._raw_output_store_port = raw_output_store_port
        self._outbox_repository = outbox_repository
        self._clock = clock
        self._audit_recorder = AuditRecorder(audit_record_repository, clock)
        self._telemetry = telemetry

    def reconcile_execution(self, command: ReconcileExecutionCommand) -> ToolRequestView:
        execution_id = ToolExecutionId(uuid.UUID(command.execution_id))
        execution = self._tool_execution_repository.find_by_id(execution_id)
        if execution is None:
            raise ToolExecutionNotFoundException(command.execution_id)

        now = self._clock.now()
        execution = execution.begin_reconciling()
        self._tool_execution_repository.save(execution, expected_status=None)

        connector = self._connector_registry_port.find_by_id(execution.connector_id)
        adapter = self._connector_registry_port.get_adapter(execution.connector_id)
        spec = ConnectorInvocationSpec(
            connector_id=str(execution.connector_id), connector_version=execution.connector_version,
            operation_key=str(execution.operation_key) if execution.operation_key else None, input_payload={},
            timeout_seconds=connector.timeout_policy.invoke_timeout_seconds if connector else 30,
        )
        outcome = adapter.reconcile(spec)

        tool_request = self._tool_request_repository.find_by_id(execution.tool_request_id)
        if tool_request is None:
            raise ToolRequestNotFoundException(str(execution.tool_request_id))

        if outcome.status is ResultStatus.SUCCESS:
            # 14-testing-strategy §"Security Tests": same "redaction failure
            # prevents publishing raw content" guarantee as execute_tool_
            # request's own SUCCESS branch.
            try:
                redacted_summary, summary_metadata = self._redaction_port.redact(outcome.summary)
                # SPEC-TG-014: same structured_output redaction gap fix as
                # execute_tool_request's own SUCCESS branch.
                redacted_structured_output, structured_output_redacted = redact_structured_output(
                    self._redaction_port, outcome.structured_output,
                )
            except Exception:
                self._telemetry.record_redaction_failure()
                raise
            any_redacted = bool(summary_metadata.redacted_fields) or structured_output_redacted
            # SPEC-TG-020: same controlled raw-output storage as
            # execute_tool_request's own SUCCESS branch.
            raw_output_ref = (
                self._raw_output_store_port.store(execution.execution_id, outcome.raw_output)
                if outcome.raw_output is not None else None
            )
            envelope = ToolResultEnvelope.create(
                result_envelope_id=ResultEnvelopeId.new_id(), execution_id=execution.execution_id, status=ResultStatus.SUCCESS,
                summary=redacted_summary, structured_output=redacted_structured_output, raw_output_ref=raw_output_ref,
                # SPEC-TG-024: same evidence-ref population as execute_tool_
                # request's own SUCCESS branch.
                evidence_refs=(raw_output_ref,) if raw_output_ref else (),
                redaction_status=RedactionStatus.REDACTED if any_redacted else RedactionStatus.NOT_REQUIRED,
            )
            saved_envelope = self._result_envelope_repository.save(envelope)
            execution = execution.reconcile_complete(saved_envelope.result_envelope_id, now)
            self._tool_execution_repository.save(execution, expected_status=None)
            tool_request = tool_request.complete(saved_envelope.result_envelope_id, now)
            tool_request = save_resolved_tool_request(
                self._tool_request_repository, tool_request, now, saved_envelope.result_envelope_id,
            )
            self._audit_recorder.record(
                action="result_published", resource_type="TOOL_EXECUTION", resource_id=str(execution_id),
                outcome=tool_request.status.name, actor_id="reconciliation-worker", correlation_id=command.correlation_id,
                tool_request_id=str(execution.tool_request_id), execution_id=str(execution_id), connector_id=str(execution.connector_id),
            )
            if tool_request.status is ToolRequestStatus.CANCELLED:
                self._outbox_repository.append(build_cancelled_event(tool_request, None, command.correlation_id, now))
            else:
                self._outbox_repository.append(
                    build_success_completed_event(tool_request, execution, saved_envelope, command.correlation_id, now)
                )
            self._telemetry.record_reconciliation("SUCCEEDED")
        elif outcome.status is ResultStatus.UNCERTAIN:
            # 10-failure-handling §"Reconciliation": never retried — see this
            # module's own docstring for why. ``retryable=False`` regardless
            # of what the connector's own outcome reported.
            execution = execution.reconcile_terminal_fail(error_code=outcome.error_code, retryable=False)
            self._tool_execution_repository.save(execution, expected_status=None)
            self._telemetry.record_reconciliation("UNCERTAIN")
            if tool_request.status is ToolRequestStatus.EXECUTING:
                tool_request = tool_request.fail(now).terminal_fail(
                    "reconciliation could not confirm the outcome; human review required", now,
                )
                tool_request = save_resolved_tool_request(self._tool_request_repository, tool_request, now)
                self._audit_recorder.record(
                    action="execution_uncertain_after_reconciliation", resource_type="TOOL_EXECUTION", resource_id=str(execution_id),
                    outcome=tool_request.status.name, actor_id="reconciliation-worker", correlation_id=command.correlation_id,
                    tool_request_id=str(execution.tool_request_id), execution_id=str(execution_id), connector_id=str(execution.connector_id),
                )
                self._telemetry.record_request_completed(tool_request.status.name)
                if tool_request.status is ToolRequestStatus.CANCELLED:
                    self._outbox_repository.append(build_cancelled_event(tool_request, None, command.correlation_id, now))
                else:
                    self._outbox_repository.append(
                        build_terminal_failed_completed_event(
                            tool_request, execution, command.correlation_id, now, status_name="UNCERTAIN",
                        )
                    )
            elif tool_request.status is ToolRequestStatus.CANCEL_REQUESTED:
                # Same reasoning as the FAILED branch's own CANCEL_REQUESTED
                # case below — a cancel already committed before reconciliation
                # started, and an outcome that couldn't even be confirmed only
                # reinforces that honoring the cancel is correct.
                tool_request = tool_request.confirm_cancelled(now)
                tool_request = self._tool_request_repository.save(tool_request, expected_status=ToolRequestStatus.CANCEL_REQUESTED)
                self._audit_recorder.record(
                    action="execution_uncertain_after_reconciliation", resource_type="TOOL_EXECUTION", resource_id=str(execution_id),
                    outcome=tool_request.status.name, actor_id="reconciliation-worker", correlation_id=command.correlation_id,
                    tool_request_id=str(execution.tool_request_id), execution_id=str(execution_id), connector_id=str(execution.connector_id),
                )
                self._outbox_repository.append(build_cancelled_event(tool_request, None, command.correlation_id, now))
            else:
                self._audit_recorder.record(
                    action="execution_uncertain_after_reconciliation", resource_type="TOOL_EXECUTION", resource_id=str(execution_id),
                    outcome="TERMINAL_FAILED", actor_id="reconciliation-worker", correlation_id=command.correlation_id,
                )
        else:
            execution = execution.reconcile_terminal_fail(error_code=outcome.error_code, retryable=outcome.retryable)
            self._tool_execution_repository.save(execution, expected_status=None)
            self._telemetry.record_reconciliation("FAILED")
            if tool_request.status is ToolRequestStatus.EXECUTING:
                # 03-state-machine has no direct EXECUTING -> TERMINAL_FAILED
                # edge; a confirmed-unrecoverable reconciliation outcome passes
                # through FAILED first, exactly like a normal retryable failure
                # would. SPEC-TG-017 UC-TG-005 step 5: "If failure is confirmed
                # and retry is allowed, a new attempt is created" — this
                # ToolExecution attempt itself is always terminal here (no
                # RECONCILING -> RETRY_SCHEDULED edge exists), but the
                # ToolRequest may still go back to QUEUED for a fresh attempt
                # rather than TERMINAL_FAILED, per the same retry policy
                # execute_tool_request's own FAILED branch consults.
                connector = self._connector_registry_port.find_by_id(execution.connector_id)
                if connector is not None and is_retry_allowed(connector, execution.attempt_number, outcome.retryable):
                    retry_not_before = compute_retry_not_before(connector, now)
                    tool_request = tool_request.fail(now).retry(now, retry_not_before=retry_not_before)
                    tool_request = save_resolved_tool_request(self._tool_request_repository, tool_request, now)
                    self._audit_recorder.record(
                        action="execution_retry_scheduled", resource_type="TOOL_EXECUTION", resource_id=str(execution_id),
                        outcome=tool_request.status.name, actor_id="reconciliation-worker", correlation_id=command.correlation_id,
                        tool_request_id=str(execution.tool_request_id), execution_id=str(execution_id), connector_id=str(execution.connector_id),
                    )
                    if tool_request.status is ToolRequestStatus.CANCELLED:
                        self._outbox_repository.append(build_cancelled_event(tool_request, None, command.correlation_id, now))
                    else:
                        self._outbox_repository.append(
                            build_retry_scheduled_event(tool_request, execution, retry_not_before, command.correlation_id, now)
                        )
                else:
                    tool_request = tool_request.fail(now).terminal_fail("reconciliation confirmed failure", now)
                    tool_request = save_resolved_tool_request(self._tool_request_repository, tool_request, now)
                    self._audit_recorder.record(
                        action="execution_failed", resource_type="TOOL_EXECUTION", resource_id=str(execution_id),
                        outcome=tool_request.status.name, actor_id="reconciliation-worker", correlation_id=command.correlation_id,
                        tool_request_id=str(execution.tool_request_id), execution_id=str(execution_id), connector_id=str(execution.connector_id),
                    )
                    self._telemetry.record_request_completed(tool_request.status.name)
                    if tool_request.status is ToolRequestStatus.CANCELLED:
                        self._outbox_repository.append(build_cancelled_event(tool_request, None, command.correlation_id, now))
                    else:
                        self._outbox_repository.append(
                            build_terminal_failed_completed_event(tool_request, execution, command.correlation_id, now)
                        )
            elif tool_request.status is ToolRequestStatus.CANCEL_REQUESTED:
                # SPEC-TG-018: a cancel already committed before reconciliation
                # even started (not a live race — ``cancel_tool_request`` no
                # longer auto-confirms; see that module's own docstring) — no
                # ``fail()``/``retry()``/``terminal_fail()`` edge exists from
                # CANCEL_REQUESTED (only {CANCELLED, COMPLETED} do), and a
                # confirmed-unrecoverable outcome only reinforces that honoring
                # the cancel is correct.
                tool_request = tool_request.confirm_cancelled(now)
                tool_request = self._tool_request_repository.save(tool_request, expected_status=ToolRequestStatus.CANCEL_REQUESTED)
                self._audit_recorder.record(
                    action="execution_failed", resource_type="TOOL_EXECUTION", resource_id=str(execution_id),
                    outcome=tool_request.status.name, actor_id="reconciliation-worker", correlation_id=command.correlation_id,
                    tool_request_id=str(execution.tool_request_id), execution_id=str(execution_id), connector_id=str(execution.connector_id),
                )
                self._outbox_repository.append(build_cancelled_event(tool_request, None, command.correlation_id, now))
            else:
                self._audit_recorder.record(
                    action="execution_failed", resource_type="TOOL_EXECUTION", resource_id=str(execution_id),
                    outcome="TERMINAL_FAILED", actor_id="reconciliation-worker", correlation_id=command.correlation_id,
                )

        current = self._tool_request_repository.find_by_id(execution.tool_request_id)
        assert current is not None
        return ToolRequestView.from_domain(current)
