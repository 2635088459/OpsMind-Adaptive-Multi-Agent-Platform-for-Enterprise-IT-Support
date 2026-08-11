"""A mutable, deterministic ClockPort implementation for tests."""

from __future__ import annotations

from datetime import datetime, timedelta


class FakeClock:
    def __init__(self, initial: datetime | None = None) -> None:
        from datetime import UTC

        self._current = initial or datetime(2026, 1, 1, tzinfo=UTC)

    def now(self) -> datetime:
        return self._current

    def advance(self, delta: timedelta) -> None:
        self._current += delta

    def set(self, instant: datetime) -> None:
        self._current = instant
