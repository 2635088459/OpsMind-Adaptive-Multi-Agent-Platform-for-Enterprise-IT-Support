"""01-domain-model §"ToolConnector": "a registered adapter for a concrete tool."
§"Capability": "the stable ability exposed to Runtime. It is not the same as a
concrete tool. Runtime should submit requests by capability; Gateway decides
which connector implements it." 01-domain-model §"Aggregate Rules": "ToolConnector
is a registry/config entity and is not part of the ToolRequest aggregate."
"""

from __future__ import annotations

import dataclasses
from dataclasses import dataclass

from tool_gateway.domain.enums import ConnectorHealthStatus, RequestedByType, RiskLevel, SideEffectKind
from tool_gateway.domain.errors import InvalidConnectorHealthTransitionException
from tool_gateway.domain.ids import ConnectorId
from tool_gateway.domain.state_machine import CONNECTOR_HEALTH_TRANSITIONS, is_allowed
from tool_gateway.domain.values import NetworkPolicy, RetryPolicy, TimeoutPolicy


def _transition(current: ConnectorHealthStatus, target: ConnectorHealthStatus) -> ConnectorHealthStatus:
    if not is_allowed(current, target, CONNECTOR_HEALTH_TRANSITIONS):
        raise InvalidConnectorHealthTransitionException(current, target)
    return target


def _require_non_blank(value: str, field_name: str) -> None:
    if not value or not value.strip():
        raise ValueError(f"{field_name} must not be blank")


@dataclass(frozen=True, slots=True)
class Capability:
    """01-domain-model §"Capability": e.g. ``ticket.enrichFromCmdb``,
    ``kubernetes.restartDeployment``. INV-TG-009: "Connector capability is not
    permission" — this value object names *what* a connector can do, never
    *who* may invoke it.
    """

    name: str

    def __post_init__(self) -> None:
        _require_non_blank(self.name, "capability name")

    def __str__(self) -> str:
        return self.name


@dataclass(frozen=True, slots=True)
class ToolConnector:
    """01-domain-model §"ToolConnector" field list, transcribed 1:1 (plus the
    NetworkPolicy/TimeoutPolicy/RetryPolicy value-object shapes for the three
    policy fields, and SideEffectKind — see that enum's own docstring).
    """

    connector_id: ConnectorId
    name: str
    version: str
    capabilities: tuple[Capability, ...]
    input_schema_ref: str
    output_schema_ref: str
    risk_level: RiskLevel
    requires_approval: bool
    side_effect_kind: SideEffectKind
    secret_requirements: tuple[str, ...]
    network_policy: NetworkPolicy
    timeout_policy: TimeoutPolicy
    retry_policy: RetryPolicy
    health_status: ConnectorHealthStatus
    allowed_requester_types: tuple[RequestedByType, ...] = ()
    """SPEC-TG-021 "Authorization Scope And Network Policy" / 02-business-
    invariants INV-TG-009: "Runtime visibility of a capability does not mean
    an Agent may execute it. Execution combines actor... connector policy."
    Empty (the default) means unrestricted — every existing connector
    registered before this spec keeps working unchanged. Non-empty is the one
    "connector policy" authorization axis buildable without inventing a
    tenant/workflow-purpose model this platform has not defined anywhere else
    (11-security's own "Authorization Model" list also names tenant/ticket
    scope/workflow purpose — those stay deferred; no tenant concept exists in
    01-domain-model or any other domain's own schema yet, and Gateway has no
    client to validate cross-domain ticket/workflow context against).
    """
    consecutive_health_check_failures: int = 0
    """SPEC-TG-030 "Crash Recovery Backpressure Scaling" 10-failure-handling
    §"Connector Crash Or Unavailability": "Consecutive failures move an ACTIVE
    connector to DEGRADED... Health check failures beyond threshold move it to
    DISABLED." Reset to 0 by any successful health check (``record_health_check_
    success()``); the ACTIVE->DEGRADED->DISABLED escalation ladder itself is
    driven by ``record_health_check_failure()`` comparing this counter against
    two threshold values the caller supplies — kept out of the domain layer,
    the same "the domain method takes the policy value as an argument, never a
    hard-coded constant" shape ``RetryPolicy.backoff_seconds``/``is_retry_
    allowed()`` already established.
    """

    @staticmethod
    def register(
        connector_id: ConnectorId,
        name: str,
        version: str,
        capabilities: tuple[Capability, ...],
        input_schema_ref: str,
        output_schema_ref: str,
        risk_level: RiskLevel,
        requires_approval: bool,
        side_effect_kind: SideEffectKind,
        secret_requirements: tuple[str, ...],
        network_policy: NetworkPolicy,
        timeout_policy: TimeoutPolicy,
        retry_policy: RetryPolicy,
        allowed_requester_types: tuple[RequestedByType, ...] = (),
    ) -> "ToolConnector":
        """04-use-cases UC-TG-007 step 5: "The connector enters ACTIVE or DISABLED
        depending on policy and health check." A newly registered connector is
        conservatively ACTIVE by default here — SPEC-TG-026/029's real
        registration health-check gate is out of this spec's scope; the
        transition methods below are what phase-04/phase-07 specs will drive.
        """

        _require_non_blank(name, "connector name")
        _require_non_blank(version, "connector version")
        if not capabilities:
            raise ValueError("a connector must declare at least one capability")
        return ToolConnector(
            connector_id=connector_id, name=name, version=version, capabilities=capabilities,
            input_schema_ref=input_schema_ref, output_schema_ref=output_schema_ref, risk_level=risk_level,
            requires_approval=requires_approval, side_effect_kind=side_effect_kind,
            secret_requirements=secret_requirements, network_policy=network_policy, timeout_policy=timeout_policy,
            retry_policy=retry_policy, health_status=ConnectorHealthStatus.ACTIVE,
            allowed_requester_types=allowed_requester_types,
        )

    def supports_capability(self, capability_name: str) -> bool:
        return any(capability.name == capability_name for capability in self.capabilities)

    def is_requester_allowed(self, requested_by_type: RequestedByType) -> bool:
        """SPEC-TG-021 INV-TG-009: an empty ``allowed_requester_types`` (the
        default) means unrestricted.
        """

        return not self.allowed_requester_types or requested_by_type in self.allowed_requester_types

    def is_host_allowed(self, host: str) -> bool:
        """SPEC-TG-021 11-security §"Network Policy": "Connector manifest must
        declare allowed host, protocol, port, and egress class. Undeclared
        endpoints are denied by default." No connector adapter in this
        codebase makes a real outbound network call yet (every registered
        adapter is an honest in-process placeholder — see
        ``EchoConnectorAdapter``'s own docstring), so there is no generic HTTP
        client layer to intercept centrally; this is the enforcement primitive
        a real network-calling adapter (a future Kubernetes/ServiceNow/Slack
        SDK integration) is expected to call before issuing any outbound
        request — ``network_policy.deny_by_default=False`` is the only escape
        hatch, matching ``NetworkPolicy``'s own field semantics.
        """

        if not self.network_policy.deny_by_default:
            return True
        return host in self.network_policy.allowed_hosts

    def is_executable(self) -> bool:
        """SPEC-TG-019 03-state-machine §"Connector Health State Machine":
        "Scheduling may select only ACTIVE connectors. A DEGRADED connector is
        allowed only for read-only or low-risk fallback unless policy
        explicitly permits otherwise." Before this spec, ``ConnectorHealthStatus.
        is_schedulable()`` (ACTIVE-only) was the sole gate used at both selection
        (``ConnectorRegistry.find_by_capability``) and execution-time
        re-verification (``execute_tool_request``'s own bound-connector check) —
        collapsing DEGRADED to fully unschedulable, stricter than this literal
        text. No ``PolicyPort`` hook exists yet for "unless policy explicitly
        permits otherwise" — phase-05 SPEC-TG-020~021 "Security And Credential
        Boundary" scope; this only implements the two hard-coded fallback
        conditions the text itself names.
        """

        if self.health_status is ConnectorHealthStatus.ACTIVE:
            return True
        if self.health_status is ConnectorHealthStatus.DEGRADED:
            return self.side_effect_kind is SideEffectKind.READ_ONLY or self.risk_level is RiskLevel.LOW
        return False

    def degrade(self) -> "ToolConnector":
        """ACTIVE -> DEGRADED."""

        return dataclasses.replace(self, health_status=_transition(self.health_status, ConnectorHealthStatus.DEGRADED))

    def reactivate(self) -> "ToolConnector":
        """{DEGRADED,DISABLED} -> ACTIVE."""

        return dataclasses.replace(self, health_status=_transition(self.health_status, ConnectorHealthStatus.ACTIVE))

    def disable(self) -> "ToolConnector":
        """{ACTIVE,DEGRADED} -> DISABLED."""

        return dataclasses.replace(self, health_status=_transition(self.health_status, ConnectorHealthStatus.DISABLED))

    def deprecate(self) -> "ToolConnector":
        """ACTIVE -> DEPRECATED."""

        return dataclasses.replace(self, health_status=_transition(self.health_status, ConnectorHealthStatus.DEPRECATED))

    def record_health_check_success(self) -> "ToolConnector":
        """SPEC-TG-030 10-failure-handling §"Connector Crash Or Unavailability".
        A single successful probe is enough to trust the connector again and
        reset the failure ladder — matches ``ConnectorHealthWorker``'s own
        pre-existing ACTIVE<->DEGRADED behavior. Never resurrects a DISABLED/
        DEPRECATED connector automatically; that stays an admin decision via
        ``reactivate()`` called directly through ``update_connector_status``.
        """

        if self.health_status is ConnectorHealthStatus.DEGRADED:
            return dataclasses.replace(self.reactivate(), consecutive_health_check_failures=0)
        return dataclasses.replace(self, consecutive_health_check_failures=0)

    def record_health_check_failure(self, degrade_after: int, disable_after: int) -> "ToolConnector":
        """SPEC-TG-030 10-failure-handling §"Connector Crash Or Unavailability":
        "Consecutive failures move an ACTIVE connector to DEGRADED... Health
        check failures beyond threshold move it to DISABLED." Only ever called
        for a connector already ACTIVE or DEGRADED — ``ConnectorHealthWorker``
        skips DISABLED/DEPRECATED connectors before probing them at all, and
        this method does not resurrect or further demote either.
        """

        failures = self.consecutive_health_check_failures + 1
        if self.health_status is ConnectorHealthStatus.ACTIVE and failures >= degrade_after:
            return dataclasses.replace(self.degrade(), consecutive_health_check_failures=failures)
        if self.health_status is ConnectorHealthStatus.DEGRADED and failures >= disable_after:
            return dataclasses.replace(self.disable(), consecutive_health_check_failures=failures)
        return dataclasses.replace(self, consecutive_health_check_failures=failures)
