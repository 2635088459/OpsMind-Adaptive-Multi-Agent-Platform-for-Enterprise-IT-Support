"""SPEC-TG-030 "Crash Recovery Backpressure Scaling" 10-failure-handling
§"Gateway Crash Recovery": "Startup recovery: 1. Replay pending outbox.
2. Scan lease-expired executions. 3. Move INVOKING executions with expired
lease to reconciliation. 4. Restore scheduling for QUEUED requests. 5. Keep
WAITING_APPROVAL requests waiting for approval event or timeout handling."

Steps 2+3 were already real (SPEC-TG-010's own ``ReclaimExpiredLeasesService``
— see that module's own docstring for exactly how CLAIMED/PREPARING/INVOKING
attempts are each resolved), and step 1 was already real
(``PublishOutboxService.dispatch()`` finds every PENDING/backoff-elapsed
FAILED row unconditionally — nothing about it is specific to "the first call
after a restart"). Steps 4 and 5 need no code at all: ``find_queued()`` and
the approval-event consumer are both already stateless/always-live — there is
no separate "resume" bookkeeping to restore for either (see this module's own
``run_recovery()`` docstring for why they are not called here).

What was actually missing is a single, explicit, callable recovery entry
point that runs steps 1-3 together and reports what it found. Before this
spec, nothing in this process ever called ``ReclaimExpiredLeasesService``/
``PublishOutboxService`` except each one's own standalone
``ExecutionWorker``/``OutboxWorker`` poll loop — and neither loop is wired to
run anywhere in this codebase yet (``run_forever()`` on both is explicitly
marked "real deployment loop", still deferred; no file in this repo ever
constructs an ``ExecutionWorker``/``OutboxWorker``/``ReconciliationWorker``/
``ConnectorHealthWorker`` outside of tests).

Deliberately NOT wired into ``main.create_app()`` to run automatically at
import time, unlike ``configure_observability()``: ``app = create_app()``
executes at module import (``main.py``'s own last line), and
``Settings.tool_gateway_persistence`` defaults to ``"postgres"`` — an eager
``run_recovery()`` call there would issue real SQL the instant anything
merely imports this module (every hermetic unit test included), turning a
currently side-effect-free import into one that requires a live database
connection just to collect tests. Exposed instead as
``POST /internal/tool-gateway/v1/admin/recovery/run`` (``api.admin_routes``)
— an operator/init-container/deploy script triggers it explicitly at actual
process startup, the same explicit-admin-action shape SPEC-TG-028's own
outbox dead-letter replay already established, rather than an implicit
background action this service's own test suite would otherwise have to work
around.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

from tool_gateway.application.commands import DispatchOutboxCommand, ReclaimExpiredLeasesCommand


@dataclass(frozen=True, slots=True)
class RecoverySummary:
    leases_reclaimed: int
    outbox_events_published: int


class _ReclaimExpiredLeasesPort(Protocol):
    """Structurally identical to ``application.ports_in.
    ReclaimExpiredLeasesUseCase`` — defined locally rather than imported from
    there to avoid a circular import (``ports_in`` itself imports
    ``RecoverySummary`` from this module for its own ``GatewayRecoveryUseCase``
    Protocol).
    """

    def reclaim_expired_leases(self, command: ReclaimExpiredLeasesCommand) -> int: ...


class _PublishOutboxPort(Protocol):
    """Structurally identical to ``application.ports_in.PublishOutboxUseCase``
    — see ``_ReclaimExpiredLeasesPort``'s own docstring for why this is not
    imported from there.
    """

    def dispatch(self, command: DispatchOutboxCommand) -> int: ...


class GatewayRecoveryService:
    def __init__(self, reclaim_port: _ReclaimExpiredLeasesPort, publish_outbox_port: _PublishOutboxPort) -> None:
        self._reclaim_port = reclaim_port
        self._publish_outbox_port = publish_outbox_port

    def run_recovery(self, batch_size: int = 200) -> RecoverySummary:
        """10-failure-handling §"Gateway Crash Recovery" steps 1-3. Step 4
        ("restore scheduling for QUEUED requests") is deliberately not called
        here — ``find_queued()`` reads live table state on every call; there
        is nothing about a QUEUED row that needs "restoring" versus any other
        moment the scheduling worker polls. Step 5 ("keep WAITING_APPROVAL
        requests waiting for approval event or timeout handling") is also
        deliberately a no-op — those rows simply sit still until
        ``approval.granted.v1``/``approval.denied.v1`` arrives; no approval
        SLA/timeout mechanism exists anywhere in this domain to "handle" (a
        genuinely deferred concept, not something this spec silently drops —
        see ``application.approve_tool_request`` module docstring for the
        wait-duration metric that is the only SLA-adjacent thing this domain
        tracks today).
        """

        reclaimed = self._reclaim_port.reclaim_expired_leases(ReclaimExpiredLeasesCommand(batch_size=batch_size))
        published = self._publish_outbox_port.dispatch(DispatchOutboxCommand(batch_size=batch_size))
        return RecoverySummary(leases_reclaimed=reclaimed, outbox_events_published=published)
