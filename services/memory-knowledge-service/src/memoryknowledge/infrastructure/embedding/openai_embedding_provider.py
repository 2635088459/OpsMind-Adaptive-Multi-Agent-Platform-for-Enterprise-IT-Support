"""A real semantic ``EmbeddingProvider`` backed by OpenAI's embeddings API —
the honest counterpart to ``DeterministicHashEmbeddingProvider`` (which the
module docstring there openly calls a placeholder that "carries no real
semantic meaning").

Deliberately a thin ``httpx`` call rather than the ``openai`` SDK: this service
already depends on ``httpx`` (nothing else here needs the SDK), one POST to
``/embeddings`` is the whole surface, and ``httpx.MockTransport`` keeps the
unit tests hermetic. ``Settings.embedding_provider`` (``"deterministic"`` by
default) selects which adapter ``container.py`` wires; the deterministic one
stays the default so no test or offline run reaches for a network or an API key.
"""

from __future__ import annotations

import uuid

import httpx

from memoryknowledge.domain.values import EmbeddingRef

_PROVIDER = "openai"


class OpenAIEmbeddingError(RuntimeError):
    """The embeddings call failed (network, auth, rate limit, malformed body).
    Ingestion already treats an embedding failure as a document-level FAILED
    with retry; retrieval catches this and degrades to the keyword path.
    """


class OpenAIEmbeddingProvider:
    def __init__(
        self,
        api_key: str,
        *,
        model: str = "text-embedding-3-small",
        base_url: str = "https://api.openai.com/v1",
        http_client: httpx.Client | None = None,
        timeout_seconds: float = 30.0,
    ) -> None:
        if not api_key:
            raise ValueError("OpenAIEmbeddingProvider requires a non-empty api_key")
        self._api_key = api_key
        self._model = model
        self._base_url = base_url.rstrip("/")
        self._client = http_client or httpx.Client(timeout=httpx.Timeout(timeout_seconds))

    def embed(self, text: str) -> tuple[EmbeddingRef, tuple[float, ...]]:
        try:
            response = self._client.post(
                f"{self._base_url}/embeddings",
                headers={"Authorization": f"Bearer {self._api_key}"},
                json={"model": self._model, "input": text},
            )
            response.raise_for_status()
            payload = response.json()
            vector = tuple(float(value) for value in payload["data"][0]["embedding"])
        except httpx.HTTPError as exc:
            raise OpenAIEmbeddingError(f"OpenAI embeddings request failed: {exc}") from exc
        except (KeyError, IndexError, TypeError, ValueError) as exc:
            raise OpenAIEmbeddingError(f"OpenAI embeddings response was malformed: {exc}") from exc

        if not vector:
            raise OpenAIEmbeddingError("OpenAI embeddings response contained an empty vector")

        embedding_ref = EmbeddingRef(
            provider=_PROVIDER, model=self._model, dimensions=len(vector), vector_id=str(uuid.uuid4())
        )
        return embedding_ref, vector
