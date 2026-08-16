"""13-package-and-class-design lists `infrastructure/embedding/`. A real embedding
model/API call (OpenAI, a local sentence-transformer, ...) is out of this spec's scope
— honesty-over-fabrication (the same posture agent-runtime-service's own
LoggingEventPublisherAdapter/otel_exporter="console" defaults take): rather than
silently faking a semantically meaningful vector, this adapter is explicitly
deterministic and hash-derived, documented as such, and named accordingly so nothing
downstream mistakes it for a trained model's output. A real provider adapter is
phase-05 (retrieval-and-knowledge-graph) scope, behind the same EmbeddingProvider port.
"""

from __future__ import annotations

import hashlib
import uuid

from memoryknowledge.domain.values import EmbeddingRef

_DIMENSIONS = 16
_PROVIDER = "deterministic-hash"
_MODEL = "sha256-projection-v1"


class DeterministicHashEmbeddingProvider:
    """Projects text into a fixed-size vector via repeated SHA-256 hashing — stable
    (same text always yields the same vector, so duplicate-content detection and unit
    tests are reproducible) but carries no real semantic meaning. SearchMemoryService
    does not rely on vector similarity from this adapter (it uses
    domain.retrieval.score_text_relevance's token-overlap proxy instead) precisely
    because this adapter cannot honestly support real semantic search.
    """

    def embed(self, text: str) -> tuple[EmbeddingRef, tuple[float, ...]]:
        digest = hashlib.sha256(text.encode()).digest()
        vector = tuple((digest[i % len(digest)] / 255.0) * 2.0 - 1.0 for i in range(_DIMENSIONS))
        embedding_ref = EmbeddingRef(provider=_PROVIDER, model=_MODEL, dimensions=_DIMENSIONS, vector_id=str(uuid.uuid4()))
        return embedding_ref, vector
