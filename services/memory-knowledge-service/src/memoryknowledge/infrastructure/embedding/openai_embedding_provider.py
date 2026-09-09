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

Transient failures (connection resets / TLS EOF / read timeouts, and 429 / 5xx
responses) are retried with exponential backoff before giving up — a document
ingest that embeds ~5 chunks in a row would otherwise fail the whole document
the first time one call hiccups on a slow link. Auth / bad-request failures
(4xx other than 429) are not retried.
"""

from __future__ import annotations

import logging
import random
import time
import uuid

import httpx

from memoryknowledge.domain.values import EmbeddingRef

logger = logging.getLogger(__name__)

_PROVIDER = "openai"
_RETRYABLE_STATUS = frozenset({429, 500, 502, 503, 504})
_MAX_BACKOFF_SECONDS = 30.0


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
        timeout_seconds: float = 60.0,
        max_attempts: int = 5,
        backoff_base_seconds: float = 1.5,
    ) -> None:
        if not api_key:
            raise ValueError("OpenAIEmbeddingProvider requires a non-empty api_key")
        if max_attempts < 1:
            raise ValueError("max_attempts must be at least 1")
        self._api_key = api_key
        self._model = model
        self._base_url = base_url.rstrip("/")
        self._client = http_client or httpx.Client(timeout=httpx.Timeout(timeout_seconds))
        self._max_attempts = max_attempts
        self._backoff_base_seconds = backoff_base_seconds

    def embed(self, text: str) -> tuple[EmbeddingRef, tuple[float, ...]]:
        vector = self._embed_with_retry(text)
        embedding_ref = EmbeddingRef(
            provider=_PROVIDER, model=self._model, dimensions=len(vector), vector_id=str(uuid.uuid4())
        )
        return embedding_ref, vector

    def _embed_with_retry(self, text: str) -> tuple[float, ...]:
        last_error: Exception | None = None
        for attempt in range(1, self._max_attempts + 1):
            try:
                return self._embed_once(text)
            except OpenAIEmbeddingError as exc:
                # Malformed / empty body — deterministic, no point retrying.
                raise exc
            except _RetryableEmbeddingError as exc:
                last_error = exc
                if attempt == self._max_attempts:
                    break
                delay = min(self._backoff_base_seconds * (2 ** (attempt - 1)), _MAX_BACKOFF_SECONDS)
                delay += random.uniform(0.0, self._backoff_base_seconds / 2 or 0.0)
                logger.warning(
                    "OpenAI embeddings attempt %d/%d failed (%s); retrying in %.1fs",
                    attempt, self._max_attempts, exc, delay,
                )
                if delay > 0:
                    time.sleep(delay)
        raise OpenAIEmbeddingError(
            f"OpenAI embeddings request failed after {self._max_attempts} attempts: {last_error}"
        ) from last_error

    def _embed_once(self, text: str) -> tuple[float, ...]:
        try:
            response = self._client.post(
                f"{self._base_url}/embeddings",
                headers={"Authorization": f"Bearer {self._api_key}"},
                json={"model": self._model, "input": text},
            )
        except httpx.HTTPError as exc:
            # Transport-level: timeout, connection reset, TLS EOF, protocol error.
            raise _RetryableEmbeddingError(f"transport error: {exc}") from exc

        if response.status_code in _RETRYABLE_STATUS:
            raise _RetryableEmbeddingError(f"HTTP {response.status_code} from OpenAI embeddings")
        try:
            response.raise_for_status()
        except httpx.HTTPStatusError as exc:
            # 400 / 401 / 403 / 404 — not worth retrying.
            raise OpenAIEmbeddingError(f"OpenAI embeddings request failed: {exc}") from exc

        try:
            payload = response.json()
            vector = tuple(float(value) for value in payload["data"][0]["embedding"])
        except (KeyError, IndexError, TypeError, ValueError) as exc:
            raise OpenAIEmbeddingError(f"OpenAI embeddings response was malformed: {exc}") from exc

        if not vector:
            raise OpenAIEmbeddingError("OpenAI embeddings response contained an empty vector")
        return vector


class _RetryableEmbeddingError(RuntimeError):
    """Internal: a transient failure worth another attempt. Never escapes the
    module — ``_embed_with_retry`` converts an exhausted retry budget into a
    public ``OpenAIEmbeddingError``.
    """
