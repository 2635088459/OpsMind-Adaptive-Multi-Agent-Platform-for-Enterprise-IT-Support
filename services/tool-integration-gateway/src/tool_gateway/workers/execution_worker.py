"""13-package-and-class-design §"workers/execution_worker.py": claims QUEUED
ToolRequests and drives one execution attempt each, via
``ExecuteToolRequestUseCase`` — never touches a repository write directly.

SPEC-TG-010 "Execution Scheduling Worker Lease" also gives this worker its own
lease-expiry reclaim pass (``reclaim_expired_leases()``) — see
``application.reclaim_expired_leases`` module docstring for why this lives
alongside claiming rather than as a separate worker file (13-package-and-class-
design's own ``workers/`` tree names no dedicated lease-reclaim module).
"""

from __future__ import annotations

import logging
import time
import uuid

from tool_gateway.application.commands import ExecuteToolRequestCommand, ReclaimExpiredLeasesCommand
from tool_gateway.application.ports_in import ExecuteToolRequestUseCase, ReclaimExpiredLeasesUseCase
from tool_gateway.ports.storage_port import ClockPort, ToolRequestRepository

logger = logging.getLogger("tool_gateway.workers.execution")


class ExecutionWorker:
    def __init__(
        self, tool_request_repository: ToolRequestRepository, execute_port: ExecuteToolRequestUseCase, worker_id: str,
        reclaim_port: ReclaimExpiredLeasesUseCase | None = None, clock: ClockPort | None = None,
    ) -> None:
        self._tool_request_repository = tool_request_repository
        self._execute_port = execute_port
        self._worker_id = worker_id
        self._reclaim_port = reclaim_port
        self._clock = clock

    def run_once(self, batch_size: int = 10) -> int:
        """Claims up to ``batch_size`` QUEUED requests (SPEC-TG-016: excluding
        any still sitting out a retry backoff) and executes one attempt each.
        Returns the number processed. ``clock`` is required to call this (kept
        optional in the constructor only so tests exercising
        ``reclaim_expired_leases()`` alone don't need one).
        """

        if self._clock is None:
            raise ValueError("ExecutionWorker.run_once() requires a clock")
        queued = self._tool_request_repository.find_queued(self._clock.now(), batch_size)
        for tool_request in queued:
            try:
                self._execute_port.execute_tool_request(ExecuteToolRequestCommand(
                    tool_request_id=str(tool_request.tool_request_id), lease_owner=self._worker_id,
                    correlation_id=str(uuid.uuid4()),
                ))
            except Exception:
                logger.exception("execution worker failed to process tool request %s", tool_request.tool_request_id)
        return len(queued)

    def reclaim_expired_leases(self, batch_size: int = 50) -> int:
        """SPEC-TG-010 09-concurrency-and-idempotency §"Worker Concurrent Claim":
        "Other workers may take over after lease expiry." Returns the number
        of lease-expired attempts reclaimed.
        """

        if self._reclaim_port is None:
            return 0
        return self._reclaim_port.reclaim_expired_leases(ReclaimExpiredLeasesCommand(batch_size=batch_size))

    def run_forever(self, poll_interval_seconds: float = 1.0) -> None:  # pragma: no cover - real deployment loop
        while True:
            self.reclaim_expired_leases()
            self.run_once()
            time.sleep(poll_interval_seconds)
