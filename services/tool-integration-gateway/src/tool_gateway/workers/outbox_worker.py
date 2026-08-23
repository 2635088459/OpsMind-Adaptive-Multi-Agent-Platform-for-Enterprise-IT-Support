"""13-package-and-class-design §"workers/outbox_worker.py": drives
``PublishOutboxUseCase`` on a poll loop.
"""

from __future__ import annotations

import logging
import time

from tool_gateway.application.commands import DispatchOutboxCommand
from tool_gateway.application.ports_in import PublishOutboxUseCase

logger = logging.getLogger("tool_gateway.workers.outbox")


class OutboxWorker:
    def __init__(self, publish_port: PublishOutboxUseCase) -> None:
        self._publish_port = publish_port

    def run_once(self, batch_size: int = 50) -> int:
        published = self._publish_port.dispatch(DispatchOutboxCommand(batch_size=batch_size))
        logger.debug("outbox worker published %d record(s)", published)
        return published

    def run_forever(self, poll_interval_seconds: float = 1.0) -> None:  # pragma: no cover - real deployment loop
        while True:
            self.run_once()
            time.sleep(poll_interval_seconds)
