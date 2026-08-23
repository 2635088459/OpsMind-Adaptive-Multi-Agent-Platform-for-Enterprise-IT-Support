"""Application-layer exceptions — raised after I/O a pure domain function must
not perform (repository lookups, uniqueness checks, capability resolution).
"""

from __future__ import annotations


class ToolRequestNotFoundException(RuntimeError):
    def __init__(self, tool_request_id: object) -> None:
        super().__init__(f"tool request {tool_request_id} was not found")
        self.tool_request_id = tool_request_id


class ToolExecutionNotFoundException(RuntimeError):
    def __init__(self, execution_id: object) -> None:
        super().__init__(f"tool execution {execution_id} was not found")
        self.execution_id = execution_id


class ResultEnvelopeNotFoundException(RuntimeError):
    def __init__(self, result_envelope_id: object) -> None:
        super().__init__(f"result envelope {result_envelope_id} was not found")
        self.result_envelope_id = result_envelope_id


class CapabilityNotRegisteredException(RuntimeError):
    """02-business-invariants INV-TG-009: a capability with no ACTIVE connector
    behind it cannot be executed — Runtime visibility of a capability name is
    not itself permission, and here it is not even executable.
    """

    def __init__(self, capability_name: str) -> None:
        super().__init__(f"capability '{capability_name}' has no registered, schedulable connector")
        self.capability_name = capability_name


class ConnectorNotFoundException(RuntimeError):
    def __init__(self, connector_id: object) -> None:
        super().__init__(f"connector {connector_id} was not found")
        self.connector_id = connector_id


class OutboxRecordNotFoundException(RuntimeError):
    def __init__(self, outbox_id: object) -> None:
        super().__init__(f"outbox record {outbox_id} was not found")
        self.outbox_id = outbox_id


class OutboxRecordNotDeadLetterException(RuntimeError):
    """SPEC-TG-028: only a DEAD_LETTER row is replayable — a PENDING row has
    no repair to make (still waiting, not stuck) and a PUBLISHED row already
    succeeded.
    """

    def __init__(self, outbox_id: object, current_status: str) -> None:
        super().__init__(f"outbox record {outbox_id} is not DEAD_LETTER (currently {current_status})")
        self.outbox_id = outbox_id
        self.current_status = current_status


class NoActiveExecutionException(RuntimeError):
    def __init__(self, tool_request_id: object) -> None:
        super().__init__(f"tool request {tool_request_id} has no active execution attempt")
        self.tool_request_id = tool_request_id


class ToolRequestIdempotencyConflictException(RuntimeError):
    """09-concurrency-and-idempotency §"Tool Request Idempotency": "Different
    payload hash: return IDEMPOTENCY_CONFLICT." The same idempotency key was
    reused with a different capability/input/reason — the caller is not
    replaying its own earlier request, it is colliding with someone else's.
    """

    def __init__(self, idempotency_key: str) -> None:
        super().__init__(f"idempotency key '{idempotency_key}' was already used with a different payload")
        self.idempotency_key = idempotency_key


class ApprovalLinkageMismatchException(RuntimeError):
    """09-concurrency-and-idempotency §"Approval Event Idempotency": "If
    approval linkage does not match, write security audit and reject." The
    incoming event's ``approvalRequestId`` does not match the ToolRequest's own
    stored linkage — never apply an approval decision from a mismatched
    approval request onto this tool request.
    """

    def __init__(self, tool_request_id: str, approval_request_id: str) -> None:
        super().__init__(f"approval request {approval_request_id} does not match the stored linkage for tool request {tool_request_id}")
        self.tool_request_id = tool_request_id
        self.approval_request_id = approval_request_id


class RawOutputForbiddenException(RuntimeError):
    """SPEC-TG-020 05-api-contracts §"Result API": ``GET /tool-results/
    {resultEnvelopeId}/raw`` "Requires privileged RBAC, audit reason, and
    policy check." No real RBAC system exists in this platform yet —
    ``ExecuteToolRequestService.find_raw_output()``'s own honest, enforceable
    proxy: only a ``RequestedByType.HUMAN_OPERATOR`` caller with a non-blank
    audit reason may pass. 11-security §"Agent Isolation": "Agent must not
    see: ... raw output" — refusing an AGENT/SYSTEM caller here is that rule
    enforced, not merely documented.
    """

    def __init__(self, result_envelope_id: object) -> None:
        super().__init__(f"raw output access to result envelope {result_envelope_id} is forbidden")
        self.result_envelope_id = result_envelope_id


class ToolRequestStatusConflictException(RuntimeError):
    """Optimistic-concurrency guard on ToolRequestRepository.save() — mirrors
    the lesson recorded for agent-runtime-service's own SPEC-ARO-003 (a bare
    read-check-write is not enough; the repository's save() must perform a
    single compare-and-swap statement/condition against the persisted status).
    """

    def __init__(self, tool_request_id: object, expected_status: object) -> None:
        super().__init__(f"tool request {tool_request_id} is no longer at expected status {expected_status}")
        self.tool_request_id = tool_request_id
        self.expected_status = expected_status
