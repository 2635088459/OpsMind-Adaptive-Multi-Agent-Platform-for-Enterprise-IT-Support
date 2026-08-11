from __future__ import annotations

from datetime import UTC, datetime


class SystemClockAdapter:
    def now(self) -> datetime:
        return datetime.now(UTC)
