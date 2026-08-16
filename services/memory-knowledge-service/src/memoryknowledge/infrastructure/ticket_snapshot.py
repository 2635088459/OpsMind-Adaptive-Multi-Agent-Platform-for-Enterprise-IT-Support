"""02-business-invariants §"状态所有权": Memory Knowledge may read a Ticket snapshot but
must never write Ticket state. No real 02-ticket-workflow client exists yet — mirrors
agent-runtime-service's own NoOpTicketSnapshotPort: returning None (no snapshot) is
honest, not a fabricated always-trusted answer. A real HTTP/event-sourced client is
phase-06 (cross-domain-contracts) scope.
"""

from __future__ import annotations

from memoryknowledge.application.records import TicketSnapshot
from memoryknowledge.domain.ids import TicketId


class NoOpTicketSnapshotPort:
    def find_snapshot(self, ticket_id: TicketId) -> TicketSnapshot | None:
        return None
