"""SPEC-TG-020 "Secret Isolation And Raw Output Access" 02-business-invariants
INV-TG-007: "tool.completed.v1 carries only summary, redacted structured
output, evidence refs, and error metadata by default. Raw output can be read
only through controlled storage references." Before this spec,
``ExecutionOutcome.raw_output`` (the connector's own untrusted, unredacted raw
content — real since SPEC-TG-001) was accepted by the domain shape but never
actually stored anywhere: ``ToolResultEnvelope.create()`` always received
``raw_output_ref=None``, so INV-TG-007's own "controlled storage references"
half of the sentence was structurally unreachable. This port is that storage
boundary — the one place raw content is allowed to be written, and the only
path back to reading it (``api.result_routes``'s own privileged
``GET /tool-results/{id}/raw``, gated by ``application.execute_tool_request.
ExecuteToolRequestService.find_raw_output()``).
"""

from __future__ import annotations

from typing import Protocol


class RawOutputStorePort(Protocol):
    def store(self, execution_id: object, raw_output: str) -> str:
        """Persists ``raw_output`` out-of-band and returns a storage reference
        (never the content itself) — the same shape discipline
        ``CredentialPort.resolve()`` already applies to secrets: this method's
        own return value is the only thing allowed to travel back into the
        domain layer.
        """
        ...

    def retrieve(self, raw_output_ref: str) -> str | None:
        """Dereferences a previously stored reference. Returns ``None`` if the
        reference is unknown/expired — never raises, so a caller can
        distinguish "no raw output was ever stored" from "storage lost it"
        without a stack trace leaking storage internals.
        """
        ...
