"""SPEC-ARO-039's own multimodal follow-up: HttpAttachmentClient, exercised against
httpx.MockTransport, never a live attachment-service instance. Mirrors
test_ticket_workflow_client.py's own pattern exactly.
"""

from __future__ import annotations

import httpx
import pytest

from agentruntime.application.exceptions import AttachmentFetchFailedException
from agentruntime.infrastructure.attachment_client import HttpAttachmentClient

pytestmark = pytest.mark.unit


def _client(handler) -> httpx.Client:
    return httpx.Client(transport=httpx.MockTransport(handler))


class _FakeTokenProvider:
    def get_token(self) -> str:
        return "service-identity-token"


def test_fetch_content_authenticates_via_the_service_identity_and_returns_real_bytes() -> None:
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["headers"] = request.headers
        return httpx.Response(200, content=b"\x89PNG-fake-bytes", headers={"content-type": "image/png"})

    client = HttpAttachmentClient("http://attachment-service:8090", _client(handler), token_provider=_FakeTokenProvider())

    content = client.fetch_content("attachment-1")

    assert content.attachment_ref == "attachment-1"
    assert content.content == b"\x89PNG-fake-bytes"
    assert content.mime_type == "image/png"
    assert captured["url"] == "http://attachment-service:8090/api/v1/attachments/attachment-1/content"
    assert captured["headers"]["authorization"] == "Bearer service-identity-token"


def test_fetch_content_raises_when_no_token_provider_is_configured() -> None:
    client = HttpAttachmentClient("http://attachment-service:8090", _client(lambda r: httpx.Response(200, content=b"")))

    with pytest.raises(AttachmentFetchFailedException):
        client.fetch_content("attachment-1")


def test_fetch_content_raises_on_a_non_200_response() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(404, json={"error": {"code": "ATTACHMENT_NOT_FOUND"}})

    client = HttpAttachmentClient("http://attachment-service:8090", _client(handler), token_provider=_FakeTokenProvider())

    with pytest.raises(AttachmentFetchFailedException):
        client.fetch_content("missing-attachment")


def test_fetch_content_raises_on_a_network_error() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused", request=request)

    client = HttpAttachmentClient("http://attachment-service:8090", _client(handler), token_provider=_FakeTokenProvider())

    with pytest.raises(AttachmentFetchFailedException):
        client.fetch_content("attachment-1")
