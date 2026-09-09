from __future__ import annotations

import httpx
import pytest

from memoryknowledge.infrastructure.embedding.openai_embedding_provider import (
    OpenAIEmbeddingError,
    OpenAIEmbeddingProvider,
)

pytestmark = pytest.mark.unit


def _provider(handler, *, max_attempts: int = 3) -> OpenAIEmbeddingProvider:
    client = httpx.Client(transport=httpx.MockTransport(handler))
    return OpenAIEmbeddingProvider(
        "sk-test", model="text-embedding-3-small", http_client=client,
        max_attempts=max_attempts, backoff_base_seconds=0.0,
    )


def test_embed_returns_a_ref_and_vector_from_the_openai_response() -> None:
    captured: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["auth"] = request.headers.get("Authorization")
        captured["body"] = request.read().decode()
        return httpx.Response(200, json={"data": [{"embedding": [0.1, 0.2, 0.3, 0.4]}]})

    ref, vector = _provider(handler).embed("how do I reset MFA")

    assert vector == (0.1, 0.2, 0.3, 0.4)
    assert ref.provider == "openai"
    assert ref.model == "text-embedding-3-small"
    assert ref.dimensions == 4
    assert ref.vector_id  # a fresh uuid
    assert captured["url"].endswith("/embeddings")
    assert captured["auth"] == "Bearer sk-test"
    assert '"text-embedding-3-small"' in captured["body"]


def test_http_error_becomes_openai_embedding_error() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(429, json={"error": {"message": "rate limited"}})

    with pytest.raises(OpenAIEmbeddingError):
        _provider(handler).embed("x")


def test_transient_failures_are_retried_then_succeed() -> None:
    calls = {"n": 0}

    def handler(_request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        if calls["n"] == 1:
            raise httpx.ReadError("TLS EOF")          # transport hiccup
        if calls["n"] == 2:
            return httpx.Response(503, json={"error": {"message": "overloaded"}})
        return httpx.Response(200, json={"data": [{"embedding": [0.1, 0.2]}]})

    ref, vector = _provider(handler).embed("x")

    assert calls["n"] == 3
    assert vector == (0.1, 0.2)
    assert ref.dimensions == 2


def test_retry_budget_is_bounded_and_then_raises() -> None:
    calls = {"n": 0}

    def handler(_request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        return httpx.Response(503)

    with pytest.raises(OpenAIEmbeddingError, match="after 3 attempts"):
        _provider(handler, max_attempts=3).embed("x")
    assert calls["n"] == 3


def test_a_401_is_not_retried() -> None:
    calls = {"n": 0}

    def handler(_request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        return httpx.Response(401, json={"error": {"message": "bad key"}})

    with pytest.raises(OpenAIEmbeddingError):
        _provider(handler, max_attempts=5).embed("x")
    assert calls["n"] == 1


def test_malformed_body_becomes_openai_embedding_error() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"unexpected": True})

    with pytest.raises(OpenAIEmbeddingError):
        _provider(handler).embed("x")


def test_empty_vector_becomes_openai_embedding_error() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"data": [{"embedding": []}]})

    with pytest.raises(OpenAIEmbeddingError):
        _provider(handler).embed("x")


def test_missing_api_key_is_rejected_at_construction() -> None:
    with pytest.raises(ValueError, match="api_key"):
        OpenAIEmbeddingProvider("")
