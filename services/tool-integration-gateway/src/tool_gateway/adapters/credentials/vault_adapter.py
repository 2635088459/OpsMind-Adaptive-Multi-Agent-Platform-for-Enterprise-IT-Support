"""13-package-and-class-design §"adapters/credentials/vault_adapter.py". A real
Vault/secret-manager integration is out of this spec's scope — this adapter
returns a deterministic, in-memory-simulated vault reference per connector, and
never holds or returns an actual secret value (INV-TG-004), matching
memory-knowledge-service's own honestly-labeled placeholder convention.

SPEC-TG-012 11-security §"Credential Management": "Connector invocation
fetches short-lived credentials on demand" — this resolves an ACTIVE
``CredentialBinding`` (SPEC-TG-001 shipped only an ephemeral, never-persisted
``CredentialHandle``; the durable ``credential_bindings`` row existed since
SPEC-TG-002 but nothing ever wrote to it). Reuses an existing binding for the
same (connector, scope) rather than minting a fresh ``vault_ref`` on every
single invocation, and bumps ``last_used_at`` on every resolve.
"""

from __future__ import annotations

import uuid

from tool_gateway.domain.credential_binding import CredentialBinding
from tool_gateway.domain.ids import ConnectorId, CredentialBindingId
from tool_gateway.ports.credential_port import CredentialHandle
from tool_gateway.ports.storage_port import ClockPort, CredentialBindingRepository


def _scope_key(secret_requirements: tuple[str, ...]) -> str:
    return ",".join(sorted(secret_requirements))


class InMemoryVaultCredentialAdapter:
    def __init__(self, credential_binding_repository: CredentialBindingRepository, clock: ClockPort) -> None:
        self._credential_binding_repository = credential_binding_repository
        self._clock = clock

    def resolve(self, connector_id: ConnectorId, secret_requirements: tuple[str, ...], risk_decision_id: str) -> CredentialHandle:
        scope = _scope_key(secret_requirements)
        now = self._clock.now()

        existing = self._credential_binding_repository.find_active(connector_id, scope)
        if existing is not None:
            self._credential_binding_repository.save(existing.mark_used(now))
            return CredentialHandle(
                credential_binding_id=str(existing.credential_binding_id), vault_ref=existing.vault_ref, scopes=secret_requirements,
            )

        binding = CredentialBinding.create(
            credential_binding_id=CredentialBindingId.new_id(), connector_id=connector_id,
            vault_ref=f"vault://tool-gateway/{connector_id}/{uuid.uuid4().hex[:8]}", scope=scope, now=now,
        )
        saved = self._credential_binding_repository.save(binding.mark_used(now))
        return CredentialHandle(credential_binding_id=str(saved.credential_binding_id), vault_ref=saved.vault_ref, scopes=secret_requirements)
