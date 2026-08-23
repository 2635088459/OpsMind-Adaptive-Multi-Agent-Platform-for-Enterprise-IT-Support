"""SPEC-TG-010 "Execution Scheduling Worker Lease": ``ExecutionWorker`` gained
its own lease-expiry reclaim pass alongside claiming — see
``workers.execution_worker`` and ``application.reclaim_expired_leases`` module
docstrings.
"""

from __future__ import annotations

from tool_gateway.application.commands import ReclaimExpiredLeasesCommand
from tool_gateway.workers.execution_worker import ExecutionWorker


class _StubReclaimPort:
    def __init__(self, reclaimed: int) -> None:
        self.reclaimed = reclaimed
        self.calls: list[ReclaimExpiredLeasesCommand] = []

    def reclaim_expired_leases(self, command: ReclaimExpiredLeasesCommand) -> int:
        self.calls.append(command)
        return self.reclaimed


def test_reclaim_expired_leases_returns_zero_when_no_reclaim_port_wired() -> None:
    """Backward compatible: a worker constructed without ``reclaim_port``
    (the pre-SPEC-TG-010 constructor shape) must not crash — it just performs
    no reclaim work.
    """

    worker = ExecutionWorker(tool_request_repository=None, execute_port=None, worker_id="worker-1")
    assert worker.reclaim_expired_leases() == 0


def test_reclaim_expired_leases_delegates_to_the_wired_port() -> None:
    stub = _StubReclaimPort(reclaimed=3)
    worker = ExecutionWorker(tool_request_repository=None, execute_port=None, worker_id="worker-1", reclaim_port=stub)

    reclaimed = worker.reclaim_expired_leases(batch_size=25)

    assert reclaimed == 3
    assert len(stub.calls) == 1
    assert stub.calls[0].batch_size == 25
