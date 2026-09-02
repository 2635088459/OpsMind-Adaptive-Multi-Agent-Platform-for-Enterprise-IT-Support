"""SPEC-ARO-039 (phase-10 Conversational Intake): HttpKnowledgeRetrievalClient,
exercised against httpx.MockTransport, never a live 04-memory-knowledge instance.
"""

from __future__ import annotations

import json

import httpx
import pytest

from agentruntime.domain.ids import WorkflowInstanceId
from agentruntime.infrastructure.knowledge_retrieval_client import (
    HttpKnowledgeRetrievalClient,
)

pytestmark = pytest.mark.unit

_WORKFLOW_INSTANCE_ID = WorkflowInstanceId.new_id()


def _client(handler) -> httpx.Client:
    return httpx.Client(transport=httpx.MockTransport(handler))


def test_search_returns_the_real_result_snippets() -> None:
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["body"] = json.loads(request.content)
        return httpx.Response(200, json={
            "retrieval_id": "11111111-1111-1111-1111-111111111111", "degraded": False,
            "results": [
                {
                    "result_type": "MEMORY", "source_id": "mem-1", "source_version": 1, "snippet": "Try restarting the VPN client.",
                    "score": 0.9, "provenance": {"source_type": "MEMORY", "source_ref": "mem-1", "redacted": False},
                },
            ],
        })

    client = HttpKnowledgeRetrievalClient("http://memory-knowledge:8010", _client(handler))

    snippets = client.search("vpn not connecting", _WORKFLOW_INSTANCE_ID, "employee-1")

    assert len(snippets) == 1
    assert snippets[0].source_id == "mem-1"
    assert snippets[0].snippet == "Try restarting the VPN client."
    assert snippets[0].score == 0.9
    assert captured["url"] == "http://memory-knowledge:8010/internal/memory/v1/search"
    assert captured["body"]["requester_id"] == "employee-1"
    assert captured["body"]["access_scope"]["classification"] == "INTERNAL"


def test_search_degrades_to_an_empty_list_on_a_network_error() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused", request=request)

    client = HttpKnowledgeRetrievalClient("http://memory-knowledge:8010", _client(handler))

    assert client.search("query", _WORKFLOW_INSTANCE_ID, "employee-1") == []


def test_search_degrades_to_an_empty_list_on_a_non_200_response() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, json={"error": "internal"})

    client = HttpKnowledgeRetrievalClient("http://memory-knowledge:8010", _client(handler))

    assert client.search("query", _WORKFLOW_INSTANCE_ID, "employee-1") == []


def test_search_degrades_to_an_empty_list_on_a_malformed_response() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"unexpected": "shape"})

    client = HttpKnowledgeRetrievalClient("http://memory-knowledge:8010", _client(handler))

    assert client.search("query", _WORKFLOW_INSTANCE_ID, "employee-1") == []
