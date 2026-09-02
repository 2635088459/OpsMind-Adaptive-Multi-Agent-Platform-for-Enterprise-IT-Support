"""SPEC-ARO-043 (phase-10 Conversational Intake): a real Keycloak client_credentials
service identity for this service's own outbound calls to 02-ticket-workflow and
06-policy-approval-governance. Structurally the same kind of client_credentials client
built as `integration-test-client` during the 2026-09-01 integration verification, but
a real, production-grade identity owned by this service rather than a throwaway test
fixture.

domain-rules: "a token is acquired once and reused/refreshed across all outbound calls
... never re-authenticated per individual request" — cached in memory only (SPEC-ARO-043
persistence: "never persisted to Postgres or any durable store"), refreshed a fixed
margin before its reported expiry. "If a token cannot be obtained, the outbound call
fails closed" — every failure path here raises OutboundAuthenticationException, never
returns a stale/expired/empty token.
"""

from __future__ import annotations

import threading
import time

import httpx

from agentruntime.application.exceptions import OutboundAuthenticationException

# Refresh this many seconds before the token's reported expiry, so a call that starts
# just before expiry doesn't race the clock mid-flight.
_EXPIRY_MARGIN_SECONDS = 10.0


class KeycloakOutboundServiceTokenProvider:
    """Settings.keycloak_token_url == "disabled" (the default — see Settings' own
    docstring) means this service has not been configured with a real Keycloak client
    yet; get_token() fails closed with a clear OutboundAuthenticationException rather
    than attempting a request against a non-URL, matching the same "safe, inert
    default, never a silent fabrication" posture event_publisher_adapter/
    agent_runtime_persistence already established for this codebase's other optional
    real-infrastructure adapters.
    """

    def __init__(self, token_url: str, client_id: str, client_secret: str, http_client: httpx.Client | None = None) -> None:
        self._token_url = token_url
        self._client_id = client_id
        self._client_secret = client_secret
        self._http_client = http_client or httpx.Client(timeout=10.0)
        self._lock = threading.Lock()
        self._cached_token: str | None = None
        self._cached_expires_at: float | None = None

    def get_token(self) -> str:
        with self._lock:
            if self._cached_token is not None and self._cached_expires_at is not None and time.monotonic() < self._cached_expires_at:
                return self._cached_token
            return self._acquire_and_cache()

    def _acquire_and_cache(self) -> str:
        if not self._token_url or self._token_url == "disabled":
            raise OutboundAuthenticationException("no Keycloak token URL is configured (settings.keycloak_token_url)")
        if not self._client_secret:
            raise OutboundAuthenticationException("no client secret is configured (settings.agent_runtime_service_client_secret)")

        try:
            response = self._http_client.post(
                self._token_url,
                data={
                    "grant_type": "client_credentials",
                    "client_id": self._client_id,
                    "client_secret": self._client_secret,
                },
                headers={"Content-Type": "application/x-www-form-urlencoded"},
            )
        except httpx.HTTPError as exc:
            raise OutboundAuthenticationException(f"token request failed: {exc}") from exc

        if response.status_code != httpx.codes.OK:
            raise OutboundAuthenticationException(f"token endpoint returned status {response.status_code}")

        try:
            body = response.json()
            access_token = body["access_token"]
            expires_in = float(body["expires_in"])
        except (ValueError, KeyError, TypeError) as exc:
            raise OutboundAuthenticationException(f"token response was malformed: {exc}") from exc

        if not access_token:
            raise OutboundAuthenticationException("token response carried a blank access_token")

        self._cached_token = access_token
        self._cached_expires_at = time.monotonic() + max(expires_in - _EXPIRY_MARGIN_SECONDS, 0.0)
        return access_token
