"""SPEC-EI-011 (case-runner-worker-lease-retry): the actual deployment loop around
CaseRunnerService — mirrors tool-integration-gateway's own
workers.execution_worker.ExecutionWorker (`run_once()` claims and drives a batch,
`reclaim_expired_leases()` recovers crashed-worker leases, `run_forever()` is the real
process loop) exactly, adapted from that domain's own SPEC-TG-010 precedent to this
one's application.ports_in.CaseRunnerPort.
"""

from __future__ import annotations

import logging
import time

from evaluationimprovement.application.ports_in import CaseRunnerPort
from evaluationimprovement.application.views import CaseRunnerReport

logger = logging.getLogger("evaluationimprovement.workers.case_runner")


class CaseRunnerWorker:
    def __init__(self, case_runner_port: CaseRunnerPort, worker_id: str) -> None:
        self._case_runner_port = case_runner_port
        self._worker_id = worker_id

    def run_once(self, batch_size: int = 10) -> CaseRunnerReport:
        try:
            return self._case_runner_port.run_once(self._worker_id, batch_size)
        except Exception:
            # CaseRunnerService.run_once() already turns every per-entry failure into
            # a queue-state transition (mark_done/mark_retry/mark_exhausted) rather
            # than raising — this guard is only for a genuinely unexpected
            # infrastructure failure (e.g. the database is down), so run_forever()'s
            # own loop survives to poll again instead of crashing the whole worker
            # process, mirroring ExecutionWorker.run_once()'s own per-request
            # try/except precedent.
            logger.exception("case runner worker %s failed during run_once()", self._worker_id)
            return CaseRunnerReport(claimed=0, completed=0, retried=0, exhausted=0)

    def reclaim_expired_leases(self, batch_size: int = 50) -> int:
        try:
            return self._case_runner_port.reclaim_expired_leases(batch_size)
        except Exception:
            logger.exception("case runner worker %s failed during reclaim_expired_leases()", self._worker_id)
            return 0

    def run_forever(self, poll_interval_seconds: float = 2.0) -> None:  # pragma: no cover - real deployment loop
        while True:
            self.reclaim_expired_leases()
            self.run_once()
            time.sleep(poll_interval_seconds)
