"""13-package-and-class-design §"ResultNormalizer": "Converts connector output
into standard ToolResultEnvelope and applies redaction." 02-business-invariants
INV-TG-007/INV-TG-004: raw output/secrets must never reach a published event,
log, or Memory document unredacted.
"""

from __future__ import annotations

from typing import Protocol

from tool_gateway.domain.values import RedactionMetadata


class RedactionPort(Protocol):
    def redact(self, raw_text: str) -> tuple[str, RedactionMetadata]:
        """Returns (redacted_text, metadata). Applied to every connector output
        before it becomes a ``ToolResultEnvelope.summary``/``structured_output``.
        """
        ...
