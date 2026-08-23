"""13-package-and-class-design §"api/connector_admin_routes.py": 04-use-cases
UC-TG-007 "Admin Registers New Connector" + 05-api-contracts §"Connector Admin
API" (`PATCH /connectors/{connectorId}/status`, `GET /capabilities`).
"""

from __future__ import annotations

from fastapi import APIRouter, Depends

from tool_gateway.api.schemas import (
    CapabilityResponse,
    ConnectorResponse,
    RegisterConnectorRequest,
    UpdateConnectorStatusRequest,
)
from tool_gateway.application.commands import RegisterConnectorCommand, UpdateConnectorStatusCommand
from tool_gateway.application.ports_in import RegisterConnectorUseCase
from tool_gateway.application.views import CapabilityView, ConnectorView
from tool_gateway.container import get_register_connector_port

router = APIRouter(prefix="/internal/tool-gateway/v1", tags=["connectors"])


def _to_response(view: ConnectorView) -> ConnectorResponse:
    return ConnectorResponse(
        connector_id=view.connector_id, name=view.name, version=view.version, capabilities=view.capabilities,
        risk_level=view.risk_level, requires_approval=view.requires_approval, health_status=view.health_status,
        side_effect_kind=view.side_effect_kind, secret_requirements=view.secret_requirements,
        allowed_hosts=view.allowed_hosts, deny_by_default=view.deny_by_default,
        connect_timeout_seconds=view.connect_timeout_seconds, invoke_timeout_seconds=view.invoke_timeout_seconds,
        max_attempts=view.max_attempts, backoff_seconds=view.backoff_seconds,
        allowed_requester_types=view.allowed_requester_types,
        consecutive_health_check_failures=view.consecutive_health_check_failures,
    )


def _capability_to_response(view: CapabilityView) -> CapabilityResponse:
    return CapabilityResponse(
        capability_name=view.capability_name, risk_level=view.risk_level, requires_approval=view.requires_approval,
        connector_count=view.connector_count,
    )


@router.post("/connectors", response_model=ConnectorResponse)
def register_connector(
    request: RegisterConnectorRequest, port: RegisterConnectorUseCase = Depends(get_register_connector_port),
) -> ConnectorResponse:
    return _to_response(port.register_connector(RegisterConnectorCommand(
        name=request.name, version=request.version, capability_names=request.capability_names,
        input_schema_ref=request.input_schema_ref, output_schema_ref=request.output_schema_ref,
        risk_level=request.risk_level, requires_approval=request.requires_approval, is_mutating=request.is_mutating,
        secret_requirements=request.secret_requirements, allowed_hosts=request.allowed_hosts,
        connect_timeout_seconds=request.connect_timeout_seconds, invoke_timeout_seconds=request.invoke_timeout_seconds,
        max_attempts=request.max_attempts, backoff_seconds=request.backoff_seconds,
        allowed_requester_types=request.allowed_requester_types, correlation_id=request.correlation_id,
    )))


@router.get("/connectors", response_model=list[ConnectorResponse])
def list_connectors(port: RegisterConnectorUseCase = Depends(get_register_connector_port)) -> list[ConnectorResponse]:
    return [_to_response(view) for view in port.list_connectors()]


@router.get("/connectors/{connector_id}", response_model=ConnectorResponse)
def find_connector(connector_id: str, port: RegisterConnectorUseCase = Depends(get_register_connector_port)) -> ConnectorResponse:
    """SPEC-TG-029 "Connector Admin Lifecycle API": single-connector lookup
    with the full manifest — ``list_connectors``'s own summary view was the
    only way to see a registered connector at all before this spec.
    """

    return _to_response(port.find_connector(connector_id))


@router.patch("/connectors/{connector_id}/status", response_model=ConnectorResponse)
def update_connector_status(
    connector_id: str, request: UpdateConnectorStatusRequest,
    port: RegisterConnectorUseCase = Depends(get_register_connector_port),
) -> ConnectorResponse:
    """05-api-contracts §"Connector Admin API": "Enable, disable, or deprecate a
    connector."
    """

    return _to_response(port.update_connector_status(UpdateConnectorStatusCommand(
        connector_id=connector_id, action=request.action, requested_by=request.requested_by,
        correlation_id=request.correlation_id,
    )))


@router.get("/capabilities", response_model=list[CapabilityResponse])
def list_capabilities(port: RegisterConnectorUseCase = Depends(get_register_connector_port)) -> list[CapabilityResponse]:
    """05-api-contracts §"Connector Admin API": "Return capability registry
    visible to Runtime." Tenant/actor/policy visibility filtering is
    11-security scope — see ``application.register_connector.
    RegisterConnectorService.list_capabilities``'s own docstring.
    """

    return [_capability_to_response(view) for view in port.list_capabilities()]
