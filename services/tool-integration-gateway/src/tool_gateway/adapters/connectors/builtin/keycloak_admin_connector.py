"""The first connector in this codebase that performs a real outbound call to a
real external system: Keycloak's Admin REST API. It backs three identity
capabilities an IT-support agent actually needs —

  * ``identity.user.unlock``      — clear a brute-force lockout + re-enable the account
  * ``identity.user.resetPassword`` — set a (temporary) password
  * ``identity.user.addToGroup``  — add the user to a realm group

Design notes, kept honest the same way ``EchoConnectorAdapter``'s docstring is:

* **Authentication is deploy-time, not per-request.** Keycloak admin access uses
  a service account configured in ``Settings`` (``KEYCLOAK_ADMIN_USERNAME`` /
  ``KEYCLOAK_ADMIN_PASSWORD``, password grant against the ``admin-cli`` client on
  the admin realm), not a per-invocation credential binding. The registered
  manifests therefore declare ``secret_requirements=()`` and the
  ``ConnectorInvocationSpec`` still carries no credential value — INV-TG-004 holds.
* **The capability is the dispatch key.** ``execute_tool_request`` builds
  ``operation_key`` as ``"{requestId}:{attempt}:{connectorId}:{capabilityName}"``
  for a MUTATING connector; the capability is its last ``:``-separated segment.
* **Secrets never appear in the outcome.** ``resetPassword`` echoes back the user
  id / username / ``temporary`` flag but never the password value
  (INV-TG-007 / connector-contract "no secret in output/log").
* **``reconcile`` performs a genuine status lookup**, not a blind re-invoke —
  ``unlock`` re-reads the brute-force counter, ``addToGroup`` re-reads the
  membership list; ``resetPassword`` has nothing observable to confirm and
  honestly reports ``UNCERTAIN``.
"""

from __future__ import annotations

import logging
import time
from typing import Any, Callable

import httpx

from tool_gateway.adapters.connectors.base import BaseConnector
from tool_gateway.domain.enums import ResultStatus
from tool_gateway.domain.values import ConnectorInvocationSpec, ExecutionOutcome

logger = logging.getLogger("tool_gateway.adapters.connectors.keycloak")

CAP_UNLOCK = "identity.user.unlock"
CAP_RESET_PASSWORD = "identity.user.resetPassword"
CAP_ADD_TO_GROUP = "identity.user.addToGroup"

_REQUIRED_KEYS: dict[str, tuple[str, ...]] = {
    CAP_UNLOCK: ("username",),
    CAP_RESET_PASSWORD: ("username", "newPassword"),
    CAP_ADD_TO_GROUP: ("username", "groupName"),
}

# password value is deliberately absent from every structured_output below.
_TOKEN_REFRESH_SKEW_SECONDS = 30


class _NotFound(Exception):
    """A named user/group the operation targeted does not exist — a
    non-retryable input problem, distinct from Keycloak being unavailable.
    """


class KeycloakAdminConnectorAdapter(BaseConnector):
    def __init__(
        self,
        *,
        base_url: str,
        realm: str,
        admin_username: str,
        admin_password: str,
        admin_realm: str = "master",
        admin_client_id: str = "admin-cli",
        http_client: httpx.Client | None = None,
        monotonic: Callable[[], float] | None = None,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._realm = realm
        self._admin_username = admin_username
        self._admin_password = admin_password
        self._admin_realm = admin_realm
        self._admin_client_id = admin_client_id
        self._client = http_client or httpx.Client(base_url=self._base_url, timeout=httpx.Timeout(10.0))
        self._monotonic = monotonic or time.monotonic
        self._token: str | None = None
        self._token_expires_at: float = 0.0

    # ----- capability dispatch -------------------------------------------------

    _HANDLERS: dict[str, str] = {
        CAP_UNLOCK: "_op_unlock",
        CAP_RESET_PASSWORD: "_op_reset_password",
        CAP_ADD_TO_GROUP: "_op_add_to_group",
    }

    @staticmethod
    def _capability_of(spec: ConnectorInvocationSpec) -> str | None:
        if not spec.operation_key:
            return None
        return spec.operation_key.rsplit(":", 1)[-1]

    def validate_input(self, spec: ConnectorInvocationSpec) -> None:
        if not isinstance(spec.input_payload, dict):
            raise ValueError("input_payload must be an object")
        capability = self._capability_of(spec)
        required = _REQUIRED_KEYS.get(capability or "")
        if required is None:
            # Unknown/absent capability: not this adapter's contract to reject
            # here — invoke() returns a FAILED ExecutionOutcome instead, keeping
            # the shared connector-contract suite (which probes with a synthetic
            # operation key) green.
            return None
        missing = [key for key in required if not str(spec.input_payload.get(key, "")).strip()]
        if missing:
            raise ValueError(f"{capability} requires non-empty {', '.join(missing)}")
        return None

    def invoke(self, spec: ConnectorInvocationSpec) -> ExecutionOutcome:
        capability = self._capability_of(spec)
        handler_name = self._HANDLERS.get(capability or "")
        if handler_name is None:
            return ExecutionOutcome(
                status=ResultStatus.FAILED, summary=f"unsupported capability '{capability}'",
                structured_output={}, raw_output=None, error_code="UNSUPPORTED_CAPABILITY", retryable=False,
            )
        try:
            summary, structured = getattr(self, handler_name)(dict(spec.input_payload))
            return ExecutionOutcome(
                status=ResultStatus.SUCCESS, summary=summary, structured_output=structured,
                raw_output=None, error_code=None, retryable=False,
            )
        except _NotFound as exc:
            return ExecutionOutcome(
                status=ResultStatus.FAILED, summary=str(exc), structured_output={},
                raw_output=None, error_code="TARGET_NOT_FOUND", retryable=False,
            )
        except httpx.TimeoutException:
            return ExecutionOutcome(
                status=ResultStatus.TIMED_OUT, summary="Keycloak admin API timed out",
                structured_output={}, raw_output=None, error_code="KEYCLOAK_TIMEOUT", retryable=True,
            )
        except httpx.HTTPStatusError as exc:
            return self._map_status_error(exc)
        except httpx.RequestError as exc:
            logger.warning("keycloak connector transport error: %s", exc)
            return ExecutionOutcome(
                status=ResultStatus.FAILED, summary="Keycloak admin API is unreachable",
                structured_output={}, raw_output=None, error_code="KEYCLOAK_UNREACHABLE", retryable=True,
            )

    def reconcile(self, spec: ConnectorInvocationSpec) -> ExecutionOutcome:
        capability = self._capability_of(spec)
        payload = dict(spec.input_payload) if isinstance(spec.input_payload, dict) else {}
        try:
            if capability == CAP_UNLOCK:
                user_id = self._user_id(str(payload.get("username", "")))
                status = self._get(f"/admin/realms/{self._realm}/attack-detection/brute-force/users/{user_id}")
                cleared = not status.get("disabled", False) and int(status.get("numFailures", 0)) == 0
                return self._reconcile_outcome(cleared, f"brute-force lock cleared={cleared}", {"userId": user_id, **status})
            if capability == CAP_ADD_TO_GROUP:
                user_id = self._user_id(str(payload.get("username", "")))
                groups = self._get(f"/admin/realms/{self._realm}/users/{user_id}/groups")
                present = any(g.get("name") == payload.get("groupName") for g in groups)
                return self._reconcile_outcome(present, f"group membership present={present}", {"userId": user_id})
        except (httpx.HTTPError, _NotFound) as exc:
            return ExecutionOutcome(
                status=ResultStatus.UNCERTAIN, summary=f"reconcile could not confirm state: {exc}",
                structured_output={}, raw_output=None, error_code="RECONCILE_INCONCLUSIVE", retryable=True,
            )
        return ExecutionOutcome(
            status=ResultStatus.UNCERTAIN, summary=f"no status lookup exists for '{capability}'",
            structured_output={}, raw_output=None, error_code=None, retryable=False,
        )

    def cancel(self, spec: ConnectorInvocationSpec) -> None:
        # Each operation is a single atomic Keycloak call — there is no
        # in-flight job to cancel once invoke() has returned.
        return None

    def health_check(self) -> bool:
        try:
            self._get(f"/admin/realms/{self._realm}")
            return True
        except Exception as exc:  # noqa: BLE001 - health probe must never raise
            logger.info("keycloak connector health check failed: %s", exc)
            return False

    # ----- operations -------------------------------------------------------

    def _op_unlock(self, payload: dict[str, Any]) -> tuple[str, dict[str, Any]]:
        username = str(payload["username"])
        user_id = self._user_id(username)
        self._delete(f"/admin/realms/{self._realm}/attack-detection/brute-force/users/{user_id}")
        self._put(f"/admin/realms/{self._realm}/users/{user_id}", json={"enabled": True})
        return (
            f"unlocked Keycloak user '{username}' (brute-force lock cleared, account enabled)",
            {"userId": user_id, "username": username, "enabled": True, "bruteForceCleared": True},
        )

    def _op_reset_password(self, payload: dict[str, Any]) -> tuple[str, dict[str, Any]]:
        username = str(payload["username"])
        temporary = bool(payload.get("temporary", True))
        user_id = self._user_id(username)
        self._put(
            f"/admin/realms/{self._realm}/users/{user_id}/reset-password",
            json={"type": "password", "value": str(payload["newPassword"]), "temporary": temporary},
        )
        # newPassword intentionally omitted from the returned structured output.
        return (
            f"reset password for Keycloak user '{username}' (temporary={temporary})",
            {"userId": user_id, "username": username, "temporary": temporary, "credentialType": "password"},
        )

    def _op_add_to_group(self, payload: dict[str, Any]) -> tuple[str, dict[str, Any]]:
        username = str(payload["username"])
        group_name = str(payload["groupName"])
        user_id = self._user_id(username)
        group_id = self._group_id(group_name)
        self._put(f"/admin/realms/{self._realm}/users/{user_id}/groups/{group_id}")
        return (
            f"added Keycloak user '{username}' to group '{group_name}'",
            {"userId": user_id, "username": username, "groupId": group_id, "groupName": group_name},
        )

    # ----- Keycloak REST helpers -----------------------------------------------

    def _user_id(self, username: str) -> str:
        if not username.strip():
            raise _NotFound("no username supplied")
        users = self._get(
            f"/admin/realms/{self._realm}/users", params={"username": username, "exact": "true"}
        )
        if not users:
            raise _NotFound(f"Keycloak user '{username}' not found in realm '{self._realm}'")
        return str(users[0]["id"])

    def _group_id(self, group_name: str) -> str:
        groups = self._get(f"/admin/realms/{self._realm}/groups", params={"search": group_name})
        match = _find_group(groups, group_name)
        if match is None:
            raise _NotFound(f"Keycloak group '{group_name}' not found in realm '{self._realm}'")
        return match

    def _bearer(self) -> str:
        if self._token is not None and self._monotonic() < self._token_expires_at:
            return self._token
        response = self._client.post(
            f"/realms/{self._admin_realm}/protocol/openid-connect/token",
            data={
                "grant_type": "password",
                "client_id": self._admin_client_id,
                "username": self._admin_username,
                "password": self._admin_password,
            },
        )
        response.raise_for_status()
        body = response.json()
        self._token = str(body["access_token"])
        self._token_expires_at = self._monotonic() + max(0, int(body.get("expires_in", 60)) - _TOKEN_REFRESH_SKEW_SECONDS)
        return self._token

    def _get(self, path: str, params: dict[str, Any] | None = None) -> Any:
        response = self._client.get(path, params=params, headers=self._auth_header())
        response.raise_for_status()
        return response.json()

    def _put(self, path: str, json: dict[str, Any] | None = None) -> None:
        response = self._client.put(path, json=json, headers=self._auth_header())
        response.raise_for_status()

    def _delete(self, path: str) -> None:
        response = self._client.delete(path, headers=self._auth_header())
        if response.status_code == httpx.codes.NOT_FOUND:
            # No active brute-force record for this user is a benign no-op, not
            # a failure — the account simply was not locked.
            return
        response.raise_for_status()

    def _auth_header(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self._bearer()}"}

    def _map_status_error(self, exc: httpx.HTTPStatusError) -> ExecutionOutcome:
        code = exc.response.status_code
        if code in (httpx.codes.UNAUTHORIZED, httpx.codes.FORBIDDEN):
            return ExecutionOutcome(
                status=ResultStatus.FAILED, summary="Keycloak rejected the admin service account",
                structured_output={}, raw_output=None, error_code="KEYCLOAK_AUTH_FAILED", retryable=False,
            )
        if code == httpx.codes.NOT_FOUND:
            return ExecutionOutcome(
                status=ResultStatus.FAILED, summary="Keycloak resource not found",
                structured_output={}, raw_output=None, error_code="TARGET_NOT_FOUND", retryable=False,
            )
        if code >= 500:
            return ExecutionOutcome(
                status=ResultStatus.FAILED, summary=f"Keycloak admin API error {code}",
                structured_output={}, raw_output=None, error_code="KEYCLOAK_UNAVAILABLE", retryable=True,
            )
        return ExecutionOutcome(
            status=ResultStatus.FAILED, summary=f"Keycloak admin API returned {code}",
            structured_output={}, raw_output=None, error_code="KEYCLOAK_ERROR", retryable=False,
        )

    @staticmethod
    def _reconcile_outcome(confirmed: bool, summary: str, structured: dict[str, Any]) -> ExecutionOutcome:
        return ExecutionOutcome(
            status=ResultStatus.SUCCESS if confirmed else ResultStatus.UNCERTAIN,
            summary=summary, structured_output=structured, raw_output=None,
            error_code=None if confirmed else "RECONCILE_INCONCLUSIVE", retryable=not confirmed,
        )


def _find_group(nodes: list[dict[str, Any]], name: str) -> str | None:
    """Keycloak's group search returns a nested forest; match by leaf name at
    any depth so a sub-group works too.
    """

    for node in nodes:
        if node.get("name") == name:
            return str(node["id"])
        found = _find_group(node.get("subGroups", []) or [], name)
        if found is not None:
            return found
    return None
