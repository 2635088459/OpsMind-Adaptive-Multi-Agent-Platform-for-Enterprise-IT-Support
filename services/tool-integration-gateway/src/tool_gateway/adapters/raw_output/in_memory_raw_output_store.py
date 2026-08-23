"""SPEC-TG-020 "Secret Isolation And Raw Output Access". A real deployment
would back this with controlled object storage (S3/GCS/etc., short retention,
access-logged) — that infrastructure integration is out of this spec's own
scope; this in-memory adapter is an honestly-labeled placeholder that still
implements the real contract (store returns an opaque reference, never the
content; retrieve requires that exact reference), mirroring this codebase's
own established convention for boundary adapters with no real backend yet
(``InMemoryVaultCredentialAdapter``, ``EchoConnectorAdapter``).
"""

from __future__ import annotations

import threading
import uuid


class InMemoryRawOutputStoreAdapter:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._by_ref: dict[str, str] = {}

    def store(self, execution_id: object, raw_output: str) -> str:
        raw_output_ref = f"raw-output://tool-gateway/{execution_id}/{uuid.uuid4().hex[:12]}"
        with self._lock:
            self._by_ref[raw_output_ref] = raw_output
        return raw_output_ref

    def retrieve(self, raw_output_ref: str) -> str | None:
        with self._lock:
            return self._by_ref.get(raw_output_ref)
