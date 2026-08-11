"""SPEC-ARO-001 module boundary placeholder for TicketSnapshotPort —
02-business-invariants §"State Ownership": "Runtime may read Ticket snapshots but
must not directly write Ticket lifecycle state." This adapter never calls Ticket
Workflow; a later spec wires the real query adapter (13-package-and-class-design
§"Adapters": "Ticket Workflow query adapter"). Always returning None keeps that
boundary honest rather than fabricating Ticket state.
"""

from __future__ import annotations

from agentruntime.application.records import TicketSnapshot
from agentruntime.domain.ids import TicketId


class NoOpTicketSnapshotPort:
    def find_snapshot(self, ticket_id: TicketId) -> TicketSnapshot | None:
        return None
