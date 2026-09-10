"""SPEC-XREL-001 — the Python event relay sidecar.

Consumes the four cross-domain events on `opsmind.events` that the Python
services (`agent-runtime`, `tool-integration-gateway`) need but have no real
RabbitMQ consumer for, transforms each to the target service's HTTP
event-endpoint contract, and POSTs it. At-least-once; every target endpoint
already dedups by `event_id`.
"""
