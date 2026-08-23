"""03-state-machine §"Connector Health State Machine"."""

from __future__ import annotations

import pytest

from tool_gateway.domain.connector import Capability, ToolConnector
from tool_gateway.domain.enums import ConnectorHealthStatus, RequestedByType, RiskLevel, SideEffectKind
from tool_gateway.domain.errors import InvalidConnectorHealthTransitionException
from tool_gateway.domain.ids import ConnectorId
from tool_gateway.domain.values import NetworkPolicy, RetryPolicy, TimeoutPolicy


def _register(
    risk_level: RiskLevel = RiskLevel.LOW, side_effect_kind: SideEffectKind = SideEffectKind.READ_ONLY,
    network_policy: NetworkPolicy | None = None, allowed_requester_types: tuple[RequestedByType, ...] = (),
) -> ToolConnector:
    return ToolConnector.register(
        connector_id=ConnectorId.new_id(), name="echo", version="1.0.0", capabilities=(Capability("demo.echo"),),
        input_schema_ref="schema://demo.echo/input/v1", output_schema_ref="schema://demo.echo/output/v1",
        risk_level=risk_level, requires_approval=False, side_effect_kind=side_effect_kind,
        secret_requirements=(), network_policy=network_policy or NetworkPolicy(allowed_hosts=()),
        timeout_policy=TimeoutPolicy(connect_timeout_seconds=5, invoke_timeout_seconds=30),
        retry_policy=RetryPolicy(max_attempts=3, backoff_seconds=5), allowed_requester_types=allowed_requester_types,
    )


def test_register_starts_active_and_schedulable() -> None:
    connector = _register()
    assert connector.health_status is ConnectorHealthStatus.ACTIVE
    assert connector.health_status.is_schedulable()
    assert connector.supports_capability("demo.echo")


def test_degrade_and_reactivate() -> None:
    connector = _register().degrade()
    assert connector.health_status is ConnectorHealthStatus.DEGRADED
    assert not connector.health_status.is_schedulable()
    reactivated = connector.reactivate()
    assert reactivated.health_status is ConnectorHealthStatus.ACTIVE


def test_disabled_cannot_deprecate_directly() -> None:
    connector = _register().disable()
    with pytest.raises(InvalidConnectorHealthTransitionException):
        connector.deprecate()


def test_is_executable_active_always_true() -> None:
    connector = _register(risk_level=RiskLevel.HIGH, side_effect_kind=SideEffectKind.MUTATING)
    assert connector.is_executable()


def test_is_executable_degraded_read_only_fallback_allowed() -> None:
    """SPEC-TG-019 03-state-machine: "A DEGRADED connector is allowed only for
    read-only or low-risk fallback."
    """

    connector = _register(risk_level=RiskLevel.HIGH, side_effect_kind=SideEffectKind.READ_ONLY).degrade()
    assert connector.is_executable()


def test_is_executable_degraded_low_risk_fallback_allowed() -> None:
    connector = _register(risk_level=RiskLevel.LOW, side_effect_kind=SideEffectKind.MUTATING).degrade()
    assert connector.is_executable()


def test_is_executable_degraded_high_risk_mutating_not_allowed() -> None:
    connector = _register(risk_level=RiskLevel.HIGH, side_effect_kind=SideEffectKind.MUTATING).degrade()
    assert not connector.is_executable()


def test_is_executable_disabled_never_allowed() -> None:
    connector = _register(risk_level=RiskLevel.LOW, side_effect_kind=SideEffectKind.READ_ONLY).disable()
    assert not connector.is_executable()


def test_is_requester_allowed_unrestricted_by_default() -> None:
    """SPEC-TG-021: an empty allowlist (the default) means unrestricted."""

    connector = _register()
    assert connector.is_requester_allowed(RequestedByType.AGENT)
    assert connector.is_requester_allowed(RequestedByType.HUMAN_OPERATOR)


def test_is_requester_allowed_restricted_to_declared_types() -> None:
    connector = _register(allowed_requester_types=(RequestedByType.HUMAN_OPERATOR,))
    assert connector.is_requester_allowed(RequestedByType.HUMAN_OPERATOR)
    assert not connector.is_requester_allowed(RequestedByType.AGENT)
    assert not connector.is_requester_allowed(RequestedByType.SYSTEM)


def test_is_host_allowed_denies_undeclared_host_by_default() -> None:
    """SPEC-TG-021 11-security §"Network Policy": "Undeclared endpoints are
    denied by default."
    """

    connector = _register(network_policy=NetworkPolicy(allowed_hosts=("api.internal.example",), deny_by_default=True))
    assert connector.is_host_allowed("api.internal.example")
    assert not connector.is_host_allowed("evil.example.com")


def test_is_host_allowed_denies_everything_when_no_hosts_declared() -> None:
    connector = _register(network_policy=NetworkPolicy(allowed_hosts=(), deny_by_default=True))
    assert not connector.is_host_allowed("anything.example.com")


def test_is_host_allowed_permits_all_when_deny_by_default_disabled() -> None:
    connector = _register(network_policy=NetworkPolicy(allowed_hosts=(), deny_by_default=False))
    assert connector.is_host_allowed("anything.example.com")


def test_register_requires_at_least_one_capability() -> None:
    with pytest.raises(ValueError):
        ToolConnector.register(
            connector_id=ConnectorId.new_id(), name="broken", version="1.0.0", capabilities=(),
            input_schema_ref="", output_schema_ref="", risk_level=RiskLevel.LOW, requires_approval=False,
            side_effect_kind=SideEffectKind.READ_ONLY, secret_requirements=(), network_policy=NetworkPolicy(allowed_hosts=()),
            timeout_policy=TimeoutPolicy(connect_timeout_seconds=5, invoke_timeout_seconds=30),
            retry_policy=RetryPolicy(max_attempts=3, backoff_seconds=5),
        )
