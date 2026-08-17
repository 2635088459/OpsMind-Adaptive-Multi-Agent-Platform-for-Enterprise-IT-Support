# 13 Package And Class Design

## Service Shape

Recommended independent service:

```text
services/tool-integration-gateway/
```

The stack should follow existing 03/04 service patterns where possible: HTTP API, PostgreSQL, RabbitMQ/event broker, transactional outbox, worker loop, and contract tests.

## Package Structure

```text
src/
  tool_gateway/
    api/
      runtime_routes.py
      connector_admin_routes.py
      result_routes.py
      schemas.py
    application/
      create_tool_request.py
      evaluate_tool_request.py
      approve_tool_request.py
      cancel_tool_request.py
      execute_tool_request.py
      reconcile_execution.py
      publish_outbox.py
    domain/
      tool_request.py
      tool_execution.py
      connector.py
      result_envelope.py
      state_machine.py
      errors.py
    ports/
      connector_port.py
      policy_port.py
      approval_port.py
      credential_port.py
      event_bus_port.py
      redaction_port.py
      storage_port.py
    adapters/
      db/
        repositories.py
        models.py
        migrations/
      events/
        rabbitmq_publisher.py
        rabbitmq_consumer.py
      connectors/
        base.py
        registry.py
        builtin/
      credentials/
        vault_adapter.py
      policy/
        policy_client.py
      redaction/
        redaction_service.py
    workers/
      execution_worker.py
      outbox_worker.py
      reconciliation_worker.py
      connector_health_worker.py
```

## Main Classes

### `ToolRequestService`

Creates requests, validates input, enforces idempotency, and links policy/approval decisions.

### `ToolExecutionService`

Handles worker claim, attempt creation, connector invocation orchestration, and result finalization.

### `ConnectorRegistry`

Registers connector manifests, resolves versions, and maps capabilities to connectors.

### `ConnectorPort`

Every connector must implement:

- `validate_input`
- `invoke`
- `reconcile`
- `cancel`
- `health_check`

### `CredentialResolver`

Returns short-lived credential handles by connector, tenant, scope, and policy decision.

### `ResultNormalizer`

Converts connector output into standard `ToolResultEnvelope` and applies redaction.

### `OutboxPublisher`

Publishes domain outbox events with retry and publish confirms.

## Dependency Direction

`domain` does not depend on framework, database, broker, or connector SDK.

`application` depends on domain and ports.

`adapters` implement ports.

`api` calls application services.

`workers` call application services and do not manipulate database directly.

