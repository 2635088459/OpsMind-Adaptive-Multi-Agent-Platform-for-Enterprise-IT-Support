"""Not in 13-package-and-class-design's literal tree — the concrete adapter for
application.ports_out.TelemetryArtifactPort, an in-memory reference store standing in
for a real artifact backend (S3/LangSmith URI). 07-data-model §"Artifact 引用": only
`artifact_provider`/`artifact_uri`/`artifact_hash`/`retention_until` are ever stored,
never the underlying payload.
"""

from __future__ import annotations

import uuid


class InMemoryTelemetryArtifactAdapter:
    def __init__(self) -> None:
        self._refs: dict[str, tuple[str, str, str, str | None]] = {}

    def store_reference(self, provider: str, uri: str, content_hash: str, retention_until: str | None) -> str:
        ref_id = str(uuid.uuid4())
        self._refs[ref_id] = (provider, uri, content_hash, retention_until)
        return ref_id
