"""Behavioral tests for the Keycloak Admin REST connector — the first built-in
connector that makes a real outbound HTTP call. Keycloak itself is stubbed with
an ``httpx.MockTransport`` so these stay fast unit tests (no Docker, no live
Keycloak); an ``@pytest.mark.integration`` round-trip against the real compose
Keycloak is a separate follow-up.
"""

from __future__ import annotations

import json

import httpx
import pytest

from tests.contracts.connector_contract import assert_connector_contract

from tool_gateway.adapters.connectors.builtin.keycloak_admin_connector import (
    CAP_ADD_TO_GROUP,
    CAP_RESET_PASSWORD,
    CAP_UNLOCK,
    KeycloakAdminConnectorAdapter,
)
from tool_gateway.domain.enums import ResultStatus
from tool_gateway.domain.values import ConnectorInvocationSpec

REALM = "opsmind"
_SECRET_PASSWORD = "S3cret-should-never-leak!"


class FakeKeycloak:
    """A scriptable Keycloak Admin API. Records every request; individual paths
    can be overridden to return errors for the failure-path tests.
    """

    def __init__(self) -> None:
        self.calls: list[tuple[str, str]] = []
        self.requests: list[httpx.Request] = []
        self.users = {"alice": "u-alice"}
        self.groups = [{"id": "g-vpn", "name": "vpn-users", "subGroups": []}]
        self.brute_force = {"numFailures": 0, "disabled": False}
        self.user_groups = [{"id": "g-vpn", "name": "vpn-users"}]
        self.overrides: dict[tuple[str, str], httpx.Response] = {}

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.calls.append((request.method, request.url.path))
        self.requests.append(request)
        key = (request.method, request.url.path)
        if key in self.overrides:
            return self.overrides[key]

        path = request.url.path
        if request.method == "POST" and path.endswith("/protocol/openid-connect/token"):
            return httpx.Response(200, json={"access_token": "admin-token", "expires_in": 300})
        if request.method == "GET" and path == f"/admin/realms/{REALM}/users":
            username = request.url.params.get("username", "")
            user_id = self.users.get(username)
            return httpx.Response(200, json=[{"id": user_id}] if user_id else [])
        if request.method == "GET" and path == f"/admin/realms/{REALM}/groups":
            return httpx.Response(200, json=self.groups)
        if request.method == "DELETE" and "/attack-detection/brute-force/users/" in path:
            return httpx.Response(204)
        if request.method == "GET" and "/attack-detection/brute-force/users/" in path:
            return httpx.Response(200, json=self.brute_force)
        if request.method == "GET" and path.endswith("/groups") and "/users/" in path:
            return httpx.Response(200, json=self.user_groups)
        if request.method == "PUT":
            return httpx.Response(204)
        if request.method == "GET" and path == f"/admin/realms/{REALM}":
            return httpx.Response(200, json={"realm": REALM})
        return httpx.Response(404, json={"error": "unmapped"})

    def body_of(self, method: str, path_suffix: str) -> dict:
        for request in self.requests:
            if request.method == method and request.url.path.endswith(path_suffix):
                return json.loads(request.content.decode() or "{}")
        raise AssertionError(f"no {method} request ending in {path_suffix}")


def _adapter(kc: FakeKeycloak) -> KeycloakAdminConnectorAdapter:
    client = httpx.Client(base_url="http://keycloak.test", transport=httpx.MockTransport(kc.handler))
    return KeycloakAdminConnectorAdapter(
        base_url="http://keycloak.test", realm=REALM, admin_username="admin", admin_password="admin",
        http_client=client,
    )


def _spec(capability: str, payload: dict) -> ConnectorInvocationSpec:
    # execute_tool_request builds operation_key as "{req}:{attempt}:{connector}:{capability}".
    return ConnectorInvocationSpec(
        connector_id="conn-1", connector_version="1.0.0",
        operation_key=f"req-1:1:conn-1:{capability}", input_payload=payload, timeout_seconds=30,
    )


def test_unlock_clears_brute_force_and_re_enables_the_account() -> None:
    kc = FakeKeycloak()
    outcome = _adapter(kc).invoke(_spec(CAP_UNLOCK, {"username": "alice"}))

    assert outcome.status is ResultStatus.SUCCESS
    assert outcome.structured_output == {
        "userId": "u-alice", "username": "alice", "enabled": True, "bruteForceCleared": True,
    }
    assert ("DELETE", f"/admin/realms/{REALM}/attack-detection/brute-force/users/u-alice") in kc.calls
    assert kc.body_of("PUT", "/users/u-alice") == {"enabled": True}


def test_reset_password_never_echoes_the_password() -> None:
    kc = FakeKeycloak()
    outcome = _adapter(kc).invoke(
        _spec(CAP_RESET_PASSWORD, {"username": "alice", "newPassword": _SECRET_PASSWORD, "temporary": True})
    )

    assert outcome.status is ResultStatus.SUCCESS
    assert _SECRET_PASSWORD not in outcome.summary
    assert _SECRET_PASSWORD not in json.dumps(outcome.structured_output)
    # ...but it IS sent to Keycloak in the reset-password call body.
    assert kc.body_of("PUT", "/users/u-alice/reset-password") == {
        "type": "password", "value": _SECRET_PASSWORD, "temporary": True,
    }


def test_add_to_group_resolves_group_by_name() -> None:
    kc = FakeKeycloak()
    outcome = _adapter(kc).invoke(_spec(CAP_ADD_TO_GROUP, {"username": "alice", "groupName": "vpn-users"}))

    assert outcome.status is ResultStatus.SUCCESS
    assert outcome.structured_output["groupId"] == "g-vpn"
    assert ("PUT", f"/admin/realms/{REALM}/users/u-alice/groups/g-vpn") in kc.calls


def test_unknown_user_is_a_non_retryable_target_not_found() -> None:
    kc = FakeKeycloak()
    outcome = _adapter(kc).invoke(_spec(CAP_UNLOCK, {"username": "ghost"}))

    assert outcome.status is ResultStatus.FAILED
    assert outcome.error_code == "TARGET_NOT_FOUND"
    assert outcome.retryable is False


def test_keycloak_5xx_is_retryable_unavailable() -> None:
    kc = FakeKeycloak()
    kc.overrides[("PUT", f"/admin/realms/{REALM}/users/u-alice")] = httpx.Response(503, json={"error": "down"})
    outcome = _adapter(kc).invoke(_spec(CAP_UNLOCK, {"username": "alice"}))

    assert outcome.status is ResultStatus.FAILED
    assert outcome.error_code == "KEYCLOAK_UNAVAILABLE"
    assert outcome.retryable is True


def test_keycloak_auth_rejection_is_not_retryable() -> None:
    kc = FakeKeycloak()
    kc.overrides[("GET", f"/admin/realms/{REALM}/users")] = httpx.Response(401, json={"error": "nope"})
    outcome = _adapter(kc).invoke(_spec(CAP_ADD_TO_GROUP, {"username": "alice", "groupName": "vpn-users"}))

    assert outcome.status is ResultStatus.FAILED
    assert outcome.error_code == "KEYCLOAK_AUTH_FAILED"
    assert outcome.retryable is False


def test_timeout_maps_to_timed_out() -> None:
    def timeout_handler(_request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("slow")

    client = httpx.Client(base_url="http://keycloak.test", transport=httpx.MockTransport(timeout_handler))
    adapter = KeycloakAdminConnectorAdapter(
        base_url="http://keycloak.test", realm=REALM, admin_username="admin", admin_password="admin", http_client=client,
    )
    outcome = adapter.invoke(_spec(CAP_UNLOCK, {"username": "alice"}))

    assert outcome.status is ResultStatus.TIMED_OUT
    assert outcome.retryable is True


def test_unsupported_capability_fails_without_raising() -> None:
    kc = FakeKeycloak()
    outcome = _adapter(kc).invoke(_spec("identity.user.deleteEverything", {"username": "alice"}))

    assert outcome.status is ResultStatus.FAILED
    assert outcome.error_code == "UNSUPPORTED_CAPABILITY"


def test_validate_input_rejects_missing_required_keys_for_a_known_capability() -> None:
    kc = FakeKeycloak()
    with pytest.raises(ValueError, match="username"):
        _adapter(kc).validate_input(_spec(CAP_UNLOCK, {}))


def test_validate_input_is_lenient_for_an_unknown_capability() -> None:
    kc = FakeKeycloak()
    # The shared connector-contract suite probes with a synthetic operation key.
    _adapter(kc).validate_input(_spec("op-1", {"anything": "goes"}))


def test_reconcile_confirms_unlock_via_a_real_status_lookup() -> None:
    kc = FakeKeycloak()
    kc.brute_force = {"numFailures": 0, "disabled": False}
    outcome = _adapter(kc).reconcile(_spec(CAP_UNLOCK, {"username": "alice"}))

    assert outcome.status is ResultStatus.SUCCESS
    assert ("GET", f"/admin/realms/{REALM}/attack-detection/brute-force/users/u-alice") in kc.calls


def test_reconcile_reports_uncertain_when_the_lock_is_still_present() -> None:
    kc = FakeKeycloak()
    kc.brute_force = {"numFailures": 3, "disabled": True}
    outcome = _adapter(kc).reconcile(_spec(CAP_UNLOCK, {"username": "alice"}))

    assert outcome.status is ResultStatus.UNCERTAIN


def test_reset_password_reconcile_is_honestly_uncertain() -> None:
    kc = FakeKeycloak()
    outcome = _adapter(kc).reconcile(_spec(CAP_RESET_PASSWORD, {"username": "alice"}))

    assert outcome.status is ResultStatus.UNCERTAIN


def test_health_check_true_when_the_admin_realm_is_reachable() -> None:
    kc = FakeKeycloak()
    assert _adapter(kc).health_check() is True


def test_health_check_false_when_keycloak_errors() -> None:
    kc = FakeKeycloak()
    kc.overrides[("GET", f"/admin/realms/{REALM}")] = httpx.Response(500)
    assert _adapter(kc).health_check() is False


def test_satisfies_the_shared_connector_contract() -> None:
    kc = FakeKeycloak()
    assert_connector_contract(_adapter(kc))


def test_admin_token_is_fetched_once_and_reused() -> None:
    kc = FakeKeycloak()
    adapter = _adapter(kc)
    adapter.invoke(_spec(CAP_UNLOCK, {"username": "alice"}))
    adapter.invoke(_spec(CAP_ADD_TO_GROUP, {"username": "alice", "groupName": "vpn-users"}))

    token_calls = [c for c in kc.calls if c[0] == "POST" and c[1].endswith("/protocol/openid-connect/token")]
    assert len(token_calls) == 1
