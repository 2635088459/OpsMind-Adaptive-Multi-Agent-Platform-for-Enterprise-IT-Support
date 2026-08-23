"""04-use-cases UC-TG-007 "Admin Registers New Connector" + 05-api-contracts
§"Connector Admin API": ``PATCH /connectors/{connectorId}/status`` and
``GET /capabilities``. Not one of the seven ``application/`` filenames
13-package-and-class-design literally lists — added the same way memory-
knowledge-service's SPEC-MK-001 extended its own LLD-listed port set:
``tool_gateway.api.connector_admin_routes`` must reach an application service
rather than ``tool_gateway.adapters`` directly (the import-linter "forbidden"
contract), and no other named module owns connector registration.

Every registered connector is bound to the deterministic
``EchoConnectorAdapter`` placeholder (see ``adapters.connectors.builtin.echo_connector``'s
own docstring) — real per-connector adapter loading (Kubernetes/ServiceNow/Slack
SDKs, etc.) is phase-03 scope (00-implementation-roadmap SPEC-TG-010~015
"Execution Worker And Connectors").

SPEC-TG-019 "Connector Health And Degraded Control" added
``apply_health_check_result()`` — the automatic ACTIVE<->DEGRADED half of
03-state-machine's own Connector Health State Machine, driven by
``workers.connector_health_worker.ConnectorHealthWorker`` — alongside this
module's own pre-existing admin-driven ENABLE/DISABLE/DEPRECATE transitions,
so both paths share one audited, event-publishing mutation surface rather than
the worker mutating ``ConnectorRegistryPort`` directly (which would bypass
audit/outbox entirely — the exact class of gap SPEC-TG-010's own
``ReclaimExpiredLeasesService`` was built to avoid for lease reclaim). Neither
path published ``tool.connector.health_changed.v1`` before this spec.

SPEC-TG-030 "Crash Recovery Backpressure Scaling" extended
``apply_health_check_result()`` with the automatic DEGRADED->DISABLED half
10-failure-handling §"Connector Crash Or Unavailability" also names ("health
check failures beyond threshold move it to DISABLED") — see that method's own
docstring for what silently never happened before this spec.
"""

from __future__ import annotations

import uuid

from tool_gateway.application.audit import AuditRecorder
from tool_gateway.application.commands import RegisterConnectorCommand, UpdateConnectorStatusCommand
from tool_gateway.application.exceptions import ConnectorNotFoundException
from tool_gateway.application.outbox_events import build_connector_health_changed_event
from tool_gateway.application.views import CapabilityView, ConnectorView
from tool_gateway.domain.connector import Capability, ToolConnector
from tool_gateway.domain.enums import ConnectorHealthStatus, RequestedByType, RiskLevel, SideEffectKind
from tool_gateway.domain.ids import ConnectorId
from tool_gateway.domain.records import OutboxRecord
from tool_gateway.domain.values import NetworkPolicy, RetryPolicy, TimeoutPolicy
from tool_gateway.ports.connector_port import ConnectorPort, ConnectorRegistryPort
from tool_gateway.ports.storage_port import AuditRecordRepository, ClockPort, OutboxRepository

_STATUS_ACTIONS = {
    "ENABLE": lambda connector: connector.reactivate(),
    "DISABLE": lambda connector: connector.disable(),
    "DEPRECATE": lambda connector: connector.deprecate(),
}
_AUDIT_ACTION_BY_STATUS_ACTION = {
    "ENABLE": "connector_enabled", "DISABLE": "connector_disabled", "DEPRECATE": "connector_deprecated",
}


class RegisterConnectorService:
    def __init__(
        self, connector_registry_port: ConnectorRegistryPort, default_adapter: ConnectorPort,
        outbox_repository: OutboxRepository, audit_record_repository: AuditRecordRepository, clock: ClockPort,
        degrade_after_failures: int = 3, disable_after_failures: int = 5,
    ) -> None:
        self._connector_registry_port = connector_registry_port
        self._default_adapter = default_adapter
        self._outbox_repository = outbox_repository
        self._clock = clock
        self._audit_recorder = AuditRecorder(audit_record_repository, clock)
        self._degrade_after_failures = degrade_after_failures
        self._disable_after_failures = disable_after_failures

    def register_connector(self, command: RegisterConnectorCommand) -> ConnectorView:
        now = self._clock.now()
        connector = ToolConnector.register(
            connector_id=ConnectorId.new_id(), name=command.name, version=command.version,
            capabilities=tuple(Capability(name) for name in command.capability_names),
            input_schema_ref=command.input_schema_ref, output_schema_ref=command.output_schema_ref,
            risk_level=RiskLevel[command.risk_level], requires_approval=command.requires_approval,
            side_effect_kind=SideEffectKind.MUTATING if command.is_mutating else SideEffectKind.READ_ONLY,
            secret_requirements=command.secret_requirements,
            network_policy=NetworkPolicy(allowed_hosts=command.allowed_hosts, deny_by_default=True),
            timeout_policy=TimeoutPolicy(
                connect_timeout_seconds=command.connect_timeout_seconds, invoke_timeout_seconds=command.invoke_timeout_seconds,
            ),
            retry_policy=RetryPolicy(max_attempts=command.max_attempts, backoff_seconds=command.backoff_seconds),
            allowed_requester_types=tuple(RequestedByType[name] for name in command.allowed_requester_types),
        )
        saved = self._connector_registry_port.register(connector, self._default_adapter)

        self._audit_recorder.record(
            action="connector_registered", resource_type="TOOL_CONNECTOR", resource_id=str(saved.connector_id),
            outcome=saved.health_status.name, actor_id="admin", correlation_id=command.correlation_id,
        )
        self._outbox_repository.append(OutboxRecord(
            outbox_id=uuid.uuid4(), aggregate_type="TOOL_CONNECTOR", aggregate_id=str(saved.connector_id),
            event_type="tool.connector.registered.v1", event_version="1.0",
            payload={
                "connectorId": str(saved.connector_id), "name": saved.name, "version": saved.version,
                "capabilities": [capability.name for capability in saved.capabilities],
            },
            occurred_at=now, correlation_id=command.correlation_id,
        ))
        return ConnectorView.from_domain(saved)

    def list_connectors(self) -> list[ConnectorView]:
        return [ConnectorView.from_domain(connector) for connector in self._connector_registry_port.list_all()]

    def find_connector(self, connector_id: str) -> ConnectorView:
        """SPEC-TG-029 "Connector Admin Lifecycle API": ``GET /connectors/
        {connectorId}`` — single-connector lookup with the full manifest
        (``list_connectors``'s own summary view was the only way to see a
        registered connector at all before this spec).
        """

        connector = self._connector_registry_port.find_by_id(ConnectorId(uuid.UUID(connector_id)))
        if connector is None:
            raise ConnectorNotFoundException(connector_id)
        return ConnectorView.from_domain(connector)

    def update_connector_status(self, command: UpdateConnectorStatusCommand) -> ConnectorView:
        """05-api-contracts §"Connector Admin API": ``PATCH /connectors/
        {connectorId}/status`` — "Enable, disable, or deprecate a connector."
        INV-TG-006: "connector disabled/enabled" is a mandatory audit action.
        """

        connector_id = ConnectorId(uuid.UUID(command.connector_id))
        connector = self._connector_registry_port.find_by_id(connector_id)
        if connector is None:
            raise ConnectorNotFoundException(command.connector_id)

        transition = _STATUS_ACTIONS[command.action]
        updated = transition(connector)
        saved = self._connector_registry_port.save(updated)

        self._audit_recorder.record(
            action=_AUDIT_ACTION_BY_STATUS_ACTION[command.action], resource_type="TOOL_CONNECTOR",
            resource_id=str(saved.connector_id), outcome=saved.health_status.name, actor_id=command.requested_by,
            correlation_id=command.correlation_id,
        )
        self._outbox_repository.append(build_connector_health_changed_event(saved, command.correlation_id, self._clock.now()))
        return ConnectorView.from_domain(saved)

    def apply_health_check_result(self, connector_id: ConnectorId, healthy: bool, correlation_id: str) -> ConnectorView:
        """SPEC-TG-019 03-state-machine §"Connector Health State Machine": the
        automatic ACTIVE<->DEGRADED half. SPEC-TG-030 10-failure-handling
        §"Connector Crash Or Unavailability" extends it with the automatic
        DEGRADED->DISABLED escalation ("health check failures beyond threshold
        move it to DISABLED") — before this spec, a connector already DEGRADED
        that kept failing every subsequent health check fell straight into the
        unconditional no-op ``else`` branch this replaces, silently ignored
        forever with no escalation path except a human admin noticing and
        calling ``update_connector_status`` on their own. Never DEPRECATED,
        which stays purely human/admin-driven. A no-op (returns the connector
        unchanged, nothing audited/published) for any connector already
        DISABLED/DEPRECATED, or when neither the health status nor the
        consecutive-failure counter actually changed.
        """

        connector = self._connector_registry_port.find_by_id(connector_id)
        if connector is None:
            raise ConnectorNotFoundException(str(connector_id))

        if connector.health_status not in (ConnectorHealthStatus.ACTIVE, ConnectorHealthStatus.DEGRADED):
            return ConnectorView.from_domain(connector)

        updated = (
            connector.record_health_check_success() if healthy
            else connector.record_health_check_failure(self._degrade_after_failures, self._disable_after_failures)
        )
        if (
            updated.health_status is connector.health_status
            and updated.consecutive_health_check_failures == connector.consecutive_health_check_failures
        ):
            return ConnectorView.from_domain(connector)

        saved = self._connector_registry_port.save(updated)
        if saved.health_status is not connector.health_status:
            self._audit_recorder.record(
                action="connector_health_changed", resource_type="TOOL_CONNECTOR", resource_id=str(saved.connector_id),
                outcome=saved.health_status.name, actor_id="connector-health-worker", correlation_id=correlation_id,
            )
            self._outbox_repository.append(build_connector_health_changed_event(saved, correlation_id, self._clock.now()))
        return ConnectorView.from_domain(saved)

    def list_capabilities(self) -> list[CapabilityView]:
        """05-api-contracts §"Connector Admin API": ``GET /capabilities`` —
        "Return capability registry visible to Runtime. Results must be
        filtered by tenant, actor, and policy visibility." Tenant/actor/policy
        filtering is 11-security scope (out of this spec's own LLD mapping —
        phase-05 SPEC-TG-020~021 "Security And Credential Boundary" owns that);
        this returns every capability backed by at least one ACTIVE-schedulable
        connector, deduplicated by capability name.
        """

        by_name: dict[str, CapabilityView] = {}
        for connector in self._connector_registry_port.list_all():
            if connector.health_status is not ConnectorHealthStatus.ACTIVE:
                continue
            for capability in connector.capabilities:
                existing = by_name.get(capability.name)
                by_name[capability.name] = CapabilityView(
                    capability_name=capability.name, risk_level=connector.risk_level.name,
                    requires_approval=connector.requires_approval,
                    connector_count=(existing.connector_count + 1) if existing else 1,
                )
        return sorted(by_name.values(), key=lambda view: view.capability_name)
