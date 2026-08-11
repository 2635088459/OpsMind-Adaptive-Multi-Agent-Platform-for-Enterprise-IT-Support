"""09-concurrency-and-idempotency §"Command Idempotency": "Start, Pause, Resume,
Complete Task, and Request Tool must include idempotencyKey ... Same key with
different request hash must return conflict." Every one of those five application
services goes through this single guard instead of reimplementing the check.
"""

from __future__ import annotations

import hashlib
import json
from typing import Callable, TypeVar

from agentruntime.application.exceptions import IdempotencyKeyReusedException
from agentruntime.application.ports_out import ClockPort, CommandIdempotencyRepository
from agentruntime.application.records import CommandIdempotencyRecord
from agentruntime.domain.ids import IdempotencyKey

T = TypeVar("T")


def compute_request_hash(payload: dict) -> str:
    """Canonical (sorted-key) JSON, so field order never causes a false-conflict."""
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


class CommandIdempotencyGuard:
    def __init__(self, command_idempotency_repository: CommandIdempotencyRepository, clock: ClockPort) -> None:
        self._command_idempotency_repository = command_idempotency_repository
        self._clock = clock

    def run(
        self,
        command_type: str,
        target_id: str,
        idempotency_key: IdempotencyKey,
        request_payload: dict,
        execute: Callable[[], T],
        to_dict: Callable[[T], dict],
        from_dict: Callable[[dict], T],
    ) -> T:
        """Looks up `idempotency_key`. Same key + same request_payload hash: `execute` is
        never called again — the cached response is decoded and returned. Same key +
        different hash: raises IdempotencyKeyReusedException. No prior record: calls
        `execute()`, then persists its result under this key before returning it.
        """
        request_hash = compute_request_hash(request_payload)
        existing = self._command_idempotency_repository.find_by_key(idempotency_key)
        if existing is not None:
            if existing.request_hash != request_hash:
                raise IdempotencyKeyReusedException()
            return from_dict(json.loads(existing.response_json))

        result = execute()

        self._command_idempotency_repository.save(CommandIdempotencyRecord(
            idempotency_key=idempotency_key, command_type=command_type, target_id=target_id,
            request_hash=request_hash, response_json=json.dumps(to_dict(result)), created_at=self._clock.now(),
            expires_at=None,
        ))
        return result
