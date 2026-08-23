"""SPEC-TG-012 01-domain-model §"CredentialBinding": "describes how execution
obtains credentials. Credential values are not stored in business tables. Only
vault references, scopes, rotation metadata, lastUsedAt, and audit references
are stored." 11-security §"Credential Management": "Gateway database stores
only vault_ref, scope, rotation version, and status." Every field below is
exactly that list — there is structurally no field this dataclass could hold a
secret value in.
"""

from __future__ import annotations

import dataclasses
from dataclasses import dataclass
from datetime import datetime
from enum import Enum, auto

from tool_gateway.domain.ids import ConnectorId, CredentialBindingId


class CredentialBindingStatus(Enum):
    """Not named by 07-data-model's own column list beyond "status" as a plain
    string — ACTIVE/REVOKED is the minimal binary this domain needs today; no
    current application service triggers a revoke (a future security-hardening
    spec, phase-05 SPEC-TG-020~021, owns that trigger), but the field must
    exist for 11-security's own "Gateway database stores ... status" to be
    meaningful at all.
    """

    ACTIVE = auto()
    REVOKED = auto()


@dataclass(frozen=True, slots=True)
class CredentialBinding:
    credential_binding_id: CredentialBindingId
    connector_id: ConnectorId
    tenant_id: str | None
    scope: str
    vault_ref: str
    rotation_version: int
    status: CredentialBindingStatus
    created_at: datetime
    last_used_at: datetime | None = None

    @staticmethod
    def create(
        credential_binding_id: CredentialBindingId, connector_id: ConnectorId, vault_ref: str, scope: str, now: datetime,
        tenant_id: str | None = None,
    ) -> "CredentialBinding":
        return CredentialBinding(
            credential_binding_id=credential_binding_id, connector_id=connector_id, tenant_id=tenant_id, scope=scope,
            vault_ref=vault_ref, rotation_version=1, status=CredentialBindingStatus.ACTIVE, created_at=now,
        )

    def mark_used(self, now: datetime) -> "CredentialBinding":
        """11-security §"Credential Management": "Credential access must create
        audit records" (the caller's own job — see
        ``application.execute_tool_request``'s ``credential_binding_resolved``
        audit action) — this only updates the ``lastUsedAt`` 07-data-model
        column itself.
        """

        return dataclasses.replace(self, last_used_at=now)

    def revoke(self) -> "CredentialBinding":
        return dataclasses.replace(self, status=CredentialBindingStatus.REVOKED)
