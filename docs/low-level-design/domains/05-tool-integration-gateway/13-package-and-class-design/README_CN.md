# 13 Package And Class Design

## 服务形态

建议实现为独立服务：

```text
services/tool-integration-gateway/
```

技术栈应尽量贴合 03/04 服务已有模式：HTTP API、PostgreSQL、RabbitMQ/event broker、transactional outbox、worker loop、contract tests。

## 包结构

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

## 主要类

### `ToolRequestService`

负责 request 创建、校验、幂等、policy/approval 链接。

### `ToolExecutionService`

负责 worker claim、attempt 创建、connector invocation orchestration、result finalization。

### `ConnectorRegistry`

负责 connector manifest 注册、版本解析、capability 到 connector 的选择。

### `ConnectorPort`

所有 connector 必须实现：

- `validate_input`
- `invoke`
- `reconcile`
- `cancel`
- `health_check`

### `CredentialResolver`

根据 connector、tenant、scope、policy decision 返回短时 credential handle。

### `ResultNormalizer`

把 connector output 转成标准 `ToolResultEnvelope`，并调用 redaction。

### `OutboxPublisher`

发布本域 outbox events，保证 retry 和 publish confirm。

## 依赖方向

`domain` 不依赖 framework、database、broker、connector SDK。

`application` 依赖 domain 和 ports。

`adapters` 实现 ports。

`api` 调用 application services。

`workers` 调用 application services，不直接操作数据库。

