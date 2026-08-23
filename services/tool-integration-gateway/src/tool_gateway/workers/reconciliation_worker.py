"""13-package-and-class-design §"workers/reconciliation_worker.py": 04-use-cases
UC-TG-005 step 3 ("Reconciliation worker queries the external system or
connector status endpoint") — drives ``ReconcileExecutionUseCase`` over every
TIMED_OUT/PARTIAL_SIDE_EFFECT attempt.
"""

from __future__ import annotations

import logging
import time
import uuid

from tool_gateway.application.commands import ReconcileExecutionCommand
from tool_gateway.application.ports_in import ReconcileExecutionUseCase
from tool_gateway.ports.storage_port import ToolExecutionRepository

logger = logging.getLogger("tool_gateway.workers.reconciliation")


class ReconciliationWorker:
    def __init__(self, tool_execution_repository: ToolExecutionRepository, reconcile_port: ReconcileExecutionUseCase) -> None:
        self._tool_execution_repository = tool_execution_repository
        self._reconcile_port = reconcile_port

    def run_once(self, batch_size: int = 10) -> int:
        reconcilable = self._tool_execution_repository.find_reconcilable(batch_size)
        for execution in reconcilable:
            try:
                self._reconcile_port.reconcile_execution(ReconcileExecutionCommand(
                    execution_id=str(execution.execution_id), correlation_id=str(uuid.uuid4()),
                ))
            except Exception:
                logger.exception("reconciliation worker failed to process execution %s", execution.execution_id)
        return len(reconcilable)

    def run_forever(self, poll_interval_seconds: float = 5.0) -> None:  # pragma: no cover - real deployment loop
        while True:
            self.run_once()
            time.sleep(poll_interval_seconds)
