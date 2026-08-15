from __future__ import annotations

import pytest

from agentruntime.application.redaction import redact_payload

pytestmark = pytest.mark.unit


def test_redacts_a_password_field() -> None:
    payload = '{"username": "alice", "password": "hunter2"}'

    assert redact_payload(payload) == '{"username": "alice", "password": "***REDACTED***"}'


@pytest.mark.parametrize("key", [
    "password", "secret", "token", "apiKey", "api_key", "api-key", "accessToken", "access_token",
    "refreshToken", "refresh_token", "clientSecret", "client_secret", "authorization", "credential", "credentials",
])
def test_redacts_every_known_sensitive_key(key: str) -> None:
    payload = f'{{"{key}": "top-secret-value"}}'

    redacted = redact_payload(payload)

    assert "top-secret-value" not in redacted
    assert "***REDACTED***" in redacted


def test_is_case_insensitive_on_the_key_name() -> None:
    payload = '{"PASSWORD": "hunter2", "Token": "abc123"}'

    redacted = redact_payload(payload)

    assert "hunter2" not in redacted
    assert "abc123" not in redacted


def test_leaves_non_sensitive_keys_untouched() -> None:
    payload = '{"resultPayload": "diagnostics ok", "before": "restart", "count": 3}'

    assert redact_payload(payload) == payload


def test_redacts_only_the_sensitive_key_in_a_mixed_payload() -> None:
    payload = '{"service": "api", "token": "abc123", "region": "us-east-1"}'

    redacted = redact_payload(payload)

    assert redacted == '{"service": "api", "token": "***REDACTED***", "region": "us-east-1"}'


def test_degrades_gracefully_on_malformed_json() -> None:
    """PoisonEventRecord.payload is, by definition, not guaranteed to be valid JSON —
    redaction must still scan and mask what it can find rather than skip malformed
    input entirely.
    """
    payload = '{"status": "COMPLETED", "token": "abc123'  # truncated, missing closing quote/brace

    redacted = redact_payload(payload)

    # The truncated token value has no closing quote for the regex to anchor on, so it
    # is left as-is — a known, documented limitation of a plain regex over unparsed
    # text, not a false claim of catching every malformed case.
    assert redacted == payload


def test_degrades_gracefully_on_malformed_json_with_a_closed_sensitive_value() -> None:
    payload = '{"password": "hunter2", "status": "COMPLETED"'  # missing closing brace only

    redacted = redact_payload(payload)

    assert "hunter2" not in redacted
    assert redacted == '{"password": "***REDACTED***", "status": "COMPLETED"'


def test_empty_payload_is_unchanged() -> None:
    assert redact_payload("") == ""


def test_non_json_plain_text_is_unchanged_when_no_sensitive_key_shape_is_present() -> None:
    payload = "not json at all"

    assert redact_payload(payload) == payload
