"""Not literally named by 13-package-and-class-design's own adapters/ tree —
added because every timestamped domain factory needs one, the same way
memory-knowledge-service's SPEC-MK-001 added its own ``infrastructure/clock.py``
beyond that service's LLD-listed adapter set.
"""

from __future__ import annotations

from datetime import UTC, datetime


class SystemClockAdapter:
    def now(self) -> datetime:
        return datetime.now(UTC)
