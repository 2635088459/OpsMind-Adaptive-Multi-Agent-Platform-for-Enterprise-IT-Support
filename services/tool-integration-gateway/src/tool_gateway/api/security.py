"""SPEC-SC-018/020 follow-up: this router had ZERO caller-identity check of any
kind (confirmed by reading every route in ``runtime_routes.py`` directly) — every
tool-request submit/approval-decision/cancel call was reachable by anyone who could
reach this service's own port, with no distinction between the one real intended
caller (``agent-runtime-service``, per INV-TG-001: "Tool Gateway is the only tool
execution entry point") and any other caller.

``X-Caller-Id``/``X-Caller-Type`` is a caller-asserted header pair, the SAME honest-
placeholder class every other Python service in this platform already uses for real
identity pending a real cross-domain JWT/workload-identity contract
(evaluation-improvement-service's own ``X-Actor-Id``/``X-Actor-Role``,
memory-knowledge-service's own ``access_scope.role``) — not a new, one-off mechanism
invented here. Distinct from ``requested_by_type``/``requested_by_id`` in
``SubmitToolRequestRequest``'s own request body, which describe who a tool request is
being made ON BEHALF OF (a domain concept, checked against a connector's own
``allowed_requester_types``) — this module is purely an HTTP-boundary "who is calling
this endpoint at all" gate, orthogonal to that.
"""

from __future__ import annotations

from fastapi import Header


class UntrustedCallerException(RuntimeError):
    """Raised by `require_service_caller` — an API-layer (not application-layer)
    exception on purpose: "was the right HTTP header sent" is an HTTP-boundary
    concern the application layer must never know about. Registered in
    `api/errors.py` alongside every other exception this service maps to its
    shared error envelope.
    """

    def __init__(self, caller_type: str) -> None:
        super().__init__(f"caller type {caller_type!r} is not permitted to submit, decide, or cancel tool requests")
        self.caller_type = caller_type


def require_service_caller(
    x_caller_id: str = Header(..., alias="X-Caller-Id"),
    x_caller_type: str = Header(..., alias="X-Caller-Type"),
) -> str:
    """Submit/approval-decision/cancel are write operations reserved for a trusted
    SERVICE caller (agent-runtime-service today) — a human browser session must
    never reach them directly, matching INV-TG-001's own literal wording.
    """
    if x_caller_type != "SERVICE":
        raise UntrustedCallerException(x_caller_type)
    return x_caller_id


def optional_caller(
    x_caller_id: str | None = Header(default=None, alias="X-Caller-Id"),
    x_caller_type: str | None = Header(default=None, alias="X-Caller-Type"),
) -> tuple[str, str]:
    """A read caller (support-console's own AiLogPanel included, SPEC-SC-006) may
    assert no identity at all and still read one tool request's own status —
    matches this platform's own established read-floor convention.
    """
    return (x_caller_id or "anonymous", x_caller_type or "HUMAN")
