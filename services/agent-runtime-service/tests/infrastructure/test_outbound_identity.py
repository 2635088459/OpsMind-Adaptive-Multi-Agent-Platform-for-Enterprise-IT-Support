"""SPEC-ARO-043 (phase-10 Conversational Intake): KeycloakOutboundServiceTokenProvider,
exercised against httpx.MockTransport, never a live Keycloak instance.
"""

from __future__ import annotations

import httpx
import pytest

from agentruntime.application.exceptions import OutboundAuthenticationException
from agentruntime.infrastructure.outbound_identity import (
    KeycloakOutboundServiceTokenProvider,
)

pytestmark = pytest.mark.unit


def _client(handler) -> httpx.Client:
    return httpx.Client(transport=httpx.MockTransport(handler))


def test_get_token_acquires_and_returns_the_access_token() -> None:
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["body"] = request.read().decode()
        return httpx.Response(200, json={"access_token": "token-1", "expires_in": 300})

    provider = KeycloakOutboundServiceTokenProvider(
        "http://keycloak/realms/opsmind/protocol/openid-connect/token", "agent-runtime-service", "s3cr3t",
        _client(handler),
    )

    token = provider.get_token()

    assert token == "token-1"
    assert "grant_type=client_credentials" in captured["body"]
    assert "client_id=agent-runtime-service" in captured["body"]
    assert "client_secret=s3cr3t" in captured["body"]


def test_get_token_reuses_a_cached_unexpired_token_without_a_second_call() -> None:
    call_count = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal call_count
        call_count += 1
        return httpx.Response(200, json={"access_token": f"token-{call_count}", "expires_in": 300})

    provider = KeycloakOutboundServiceTokenProvider("http://keycloak/token", "client-1", "secret-1", _client(handler))

    first = provider.get_token()
    second = provider.get_token()

    assert first == second == "token-1"
    assert call_count == 1


def test_get_token_refreshes_once_the_cached_token_has_expired() -> None:
    call_count = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal call_count
        call_count += 1
        # expires_in shorter than the provider's own refresh margin forces every call
        # to be treated as already-expired.
        return httpx.Response(200, json={"access_token": f"token-{call_count}", "expires_in": 1})

    provider = KeycloakOutboundServiceTokenProvider("http://keycloak/token", "client-1", "secret-1", _client(handler))

    first = provider.get_token()
    second = provider.get_token()

    assert first == "token-1"
    assert second == "token-2"
    assert call_count == 2


def test_get_token_fails_closed_when_no_token_url_is_configured() -> None:
    provider = KeycloakOutboundServiceTokenProvider("disabled", "client-1", "secret-1")

    with pytest.raises(OutboundAuthenticationException):
        provider.get_token()


def test_get_token_fails_closed_when_no_client_secret_is_configured() -> None:
    provider = KeycloakOutboundServiceTokenProvider("http://keycloak/token", "client-1", "")

    with pytest.raises(OutboundAuthenticationException):
        provider.get_token()


def test_get_token_fails_closed_on_a_non_200_response() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401, json={"error": "invalid_client"})

    provider = KeycloakOutboundServiceTokenProvider("http://keycloak/token", "client-1", "wrong-secret", _client(handler))

    with pytest.raises(OutboundAuthenticationException):
        provider.get_token()


def test_get_token_fails_closed_on_a_malformed_response_body() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"unexpected": "shape"})

    provider = KeycloakOutboundServiceTokenProvider("http://keycloak/token", "client-1", "secret-1", _client(handler))

    with pytest.raises(OutboundAuthenticationException):
        provider.get_token()
