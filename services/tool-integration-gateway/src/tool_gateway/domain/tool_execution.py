"""01-domain-model §"ToolExecution": "one execution attempt for a ToolRequest."
03-state-machine §"Execution Attempt State Machine" is enforced through
tool_gateway.domain.state_machine.TOOL_EXECUTION_TRANSITIONS.
"""

from __future__ import annotations

import dataclasses
from dataclasses import dataclass
from datetime import datetime

from tool_gateway.domain.enums import SideEffectKind, ToolExecutionStatus
from tool_gateway.domain.errors import InvalidToolExecutionTransitionException, MutationConnectorMissingOperationKeyException
from tool_gateway.domain.ids import ConnectorId, OperationKey, ResultEnvelopeId, ToolExecutionId, ToolRequestId


def _transition(current: ToolExecutionStatus, target: ToolExecutionStatus) -> ToolExecutionStatus:
    from tool_gateway.domain.state_machine import TOOL_EXECUTION_TRANSITIONS, is_allowed

    if not is_allowed(current, target, TOOL_EXECUTION_TRANSITIONS):
        raise InvalidToolExecutionTransitionException(current, target)
    return target


@dataclass(frozen=True, slots=True)
class ToolExecution:
    """01-domain-model §"ToolExecution" field list, transcribed 1:1, plus
    ``side_effect_kind`` — see SideEffectKind's own docstring for why that
    extension is necessary to enforce domain-rules' "Mutation connectors must
    have an operation key" mechanically.
    """

    execution_id: ToolExecutionId
    tool_request_id: ToolRequestId
    attempt_number: int
    connector_id: ConnectorId
    connector_version: str
    side_effect_kind: SideEffectKind
    status: ToolExecutionStatus
    operation_key: OperationKey | None = None
    lease_owner: str | None = None
    lease_expires_at: datetime | None = None
    started_at: datetime | None = None
    completed_at: datetime | None = None
    timeout_at: datetime | None = None
    result_envelope_id: ResultEnvelopeId | None = None
    error_code: str | None = None
    retryable: bool = False
    """SPEC-TG-016: 07-data-model's own ``tool_executions`` column list names
    ``error_code``/``retryable`` (the connector's own ``ExecutionOutcome``
    classification for this specific attempt) — present in the Postgres row
    model and migration since SPEC-TG-002, but no domain field ever carried
    them, so every write silently persisted the column defaults
    (``NULL``/``false``) regardless of what the connector actually reported.
    Set by ``fail_invoking()``/``reconcile_terminal_fail()``, the two
    transitions a real connector-reported failure classification reaches.
    """

    @staticmethod
    def create(
        execution_id: ToolExecutionId,
        tool_request_id: ToolRequestId,
        attempt_number: int,
        connector_id: ConnectorId,
        connector_version: str,
        side_effect_kind: SideEffectKind,
        operation_key: OperationKey | None,
    ) -> "ToolExecution":
        """domain-rules §"Required": "Mutation connectors must have an operation
        key." INV-TG-003: "Every connector that may mutate an external system
        must have an operationKey."
        """

        if side_effect_kind is SideEffectKind.MUTATING and operation_key is None:
            raise MutationConnectorMissingOperationKeyException()
        return ToolExecution(
            execution_id=execution_id, tool_request_id=tool_request_id, attempt_number=attempt_number,
            connector_id=connector_id, connector_version=connector_version, side_effect_kind=side_effect_kind,
            status=ToolExecutionStatus.CREATED, operation_key=operation_key,
        )

    def claim(self, lease_owner: str, lease_expires_at: datetime, now: datetime) -> "ToolExecution":
        """CREATED -> CLAIMED. 13-package-and-class-design §"ToolExecutionService":
        "Handles worker claim."
        """

        return dataclasses.replace(
            self, status=_transition(self.status, ToolExecutionStatus.CLAIMED),
            lease_owner=lease_owner, lease_expires_at=lease_expires_at, started_at=now,
        )

    def begin_preparing(self) -> "ToolExecution":
        """CLAIMED -> PREPARING."""

        return dataclasses.replace(self, status=_transition(self.status, ToolExecutionStatus.PREPARING))

    def begin_invoking(self, timeout_at: datetime) -> "ToolExecution":
        """PREPARING -> INVOKING."""

        return dataclasses.replace(self, status=_transition(self.status, ToolExecutionStatus.INVOKING), timeout_at=timeout_at)

    def begin_normalizing(self) -> "ToolExecution":
        """INVOKING -> NORMALIZING_RESULT."""

        return dataclasses.replace(self, status=_transition(self.status, ToolExecutionStatus.NORMALIZING_RESULT))

    def complete(self, result_envelope_id: ResultEnvelopeId, now: datetime) -> "ToolExecution":
        """NORMALIZING_RESULT -> COMPLETED."""

        return dataclasses.replace(
            self, status=_transition(self.status, ToolExecutionStatus.COMPLETED),
            result_envelope_id=result_envelope_id, completed_at=now,
        )

    def expire_lease(self) -> "ToolExecution":
        """CLAIMED -> LEASE_EXPIRED."""

        return dataclasses.replace(self, status=_transition(self.status, ToolExecutionStatus.LEASE_EXPIRED))

    def fail_preparing(self) -> "ToolExecution":
        """PREPARING -> FAILED."""

        return dataclasses.replace(self, status=_transition(self.status, ToolExecutionStatus.FAILED))

    def time_out(self) -> "ToolExecution":
        """INVOKING -> TIMED_OUT."""

        return dataclasses.replace(self, status=_transition(self.status, ToolExecutionStatus.TIMED_OUT))

    def fail_invoking(self, error_code: str | None = None, retryable: bool = False) -> "ToolExecution":
        """INVOKING -> FAILED."""

        return dataclasses.replace(
            self, status=_transition(self.status, ToolExecutionStatus.FAILED), error_code=error_code, retryable=retryable,
        )

    def mark_partial_side_effect(self) -> "ToolExecution":
        """INVOKING -> PARTIAL_SIDE_EFFECT. 04-use-cases UC-TG-005 step 2."""

        return dataclasses.replace(self, status=_transition(self.status, ToolExecutionStatus.PARTIAL_SIDE_EFFECT))

    def fail_normalizing(self) -> "ToolExecution":
        """NORMALIZING_RESULT -> FAILED."""

        return dataclasses.replace(self, status=_transition(self.status, ToolExecutionStatus.FAILED))

    def schedule_retry(self) -> "ToolExecution":
        """FAILED -> RETRY_SCHEDULED. SPEC-TG-016: a bookkeeping marker on THIS
        attempt only — the actual next attempt is a fresh ``ToolExecution`` row
        ``execute_tool_request`` creates once the worker re-claims the request
        after it re-enters QUEUED; nothing ever transitions this same object
        further (``TOOL_EXECUTION_TRANSITIONS[RETRY_SCHEDULED]`` is empty).
        """

        return dataclasses.replace(self, status=_transition(self.status, ToolExecutionStatus.RETRY_SCHEDULED))

    def begin_reconciling(self) -> "ToolExecution":
        """{TIMED_OUT,PARTIAL_SIDE_EFFECT} -> RECONCILING. 04-use-cases UC-TG-005
        step 3.
        """

        return dataclasses.replace(self, status=_transition(self.status, ToolExecutionStatus.RECONCILING))

    def reconcile_complete(self, result_envelope_id: ResultEnvelopeId, now: datetime) -> "ToolExecution":
        """RECONCILING -> COMPLETED. 04-use-cases UC-TG-005 step 4."""

        return dataclasses.replace(
            self, status=_transition(self.status, ToolExecutionStatus.COMPLETED),
            result_envelope_id=result_envelope_id, completed_at=now,
        )

    def reconcile_terminal_fail(self, error_code: str | None = None, retryable: bool = False) -> "ToolExecution":
        """RECONCILING -> TERMINAL_FAILED. 04-use-cases UC-TG-005 step 5: "If
        failure is confirmed and retry is allowed, a new attempt is created."
        This attempt itself is always terminal here (no RECONCILING ->
        RETRY_SCHEDULED edge exists) — whether a *new* attempt actually gets
        created for the ToolRequest is ``application.retry_helpers``'s own
        decision, driven by ``retryable`` and the connector's own retry policy.
        """

        return dataclasses.replace(
            self, status=_transition(self.status, ToolExecutionStatus.TERMINAL_FAILED), error_code=error_code, retryable=retryable,
        )
