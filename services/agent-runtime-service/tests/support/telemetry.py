"""SPEC-ARO-034: a shared, hermetic RuntimeTelemetry/AuditRecorder pair for
application-service unit tests — every service constructor now takes both, so tests
that only care about their own service's existing behavior use this instead of each
re-deriving its own wiring.
"""

from __future__ import annotations

from agentruntime.application.services.audit import AuditRecorder
from agentruntime.application.telemetry import RuntimeTelemetry
from agentruntime.infrastructure.persistence.in_memory import InMemoryAuditRecordRepository
from tests.support.clock import FakeClock


def build_telemetry_collaborators(clock: FakeClock) -> tuple[RuntimeTelemetry, AuditRecorder]:
    return RuntimeTelemetry(), AuditRecorder(InMemoryAuditRecordRepository(), clock)
