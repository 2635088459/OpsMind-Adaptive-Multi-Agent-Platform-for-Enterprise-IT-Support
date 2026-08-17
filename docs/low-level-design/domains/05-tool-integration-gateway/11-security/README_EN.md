# 11 Security

## Security Boundary

Tool Gateway is the security boundary for credentials and external side effects. Every action crossing the platform boundary must perform authentication, authorization, redaction, audit, and network control here.

## Credential Management

- Credentials live in an external secret/vault system.
- Gateway database stores only `vault_ref`, scope, rotation version, and status.
- Connector invocation fetches short-lived credentials on demand.
- Credentials must not be written to logs, events, results, memory, or checkpoints.
- Credential access must create audit records.

## Authorization Model

Execution authorization combines:

- tenant
- actor type/id
- ticket scope
- workflow purpose
- capability risk
- connector policy
- approval decision
- credential binding scope

Low-risk read-only tools still require authorization checks.

## Agent Isolation

Agent may see only:

- capability name
- input schema
- allowed parameter hints
- redacted result summary

Agent must not see:

- connector credential
- vault reference
- network endpoint secret
- raw output
- admin-only connector metadata

## Output Redaction

Every connector result must pass classification/redaction:

- secret/token/key
- PII
- customer data
- infrastructure internal address
- privileged diagnostic output

Only redacted output may enter event payloads or Memory Knowledge.

## Network Policy

Connector manifest must declare allowed host, protocol, port, and egress class. Undeclared endpoints are denied by default.

High-risk connectors should run in isolated worker pools.

## Audit

Audit records must include:

- who requested
- what capability
- why requested
- who approved
- which connector
- which credential binding
- what operation key
- result status
- redaction status

Audit records cannot be deleted by ordinary admins; they may only be archived by retention policy.

