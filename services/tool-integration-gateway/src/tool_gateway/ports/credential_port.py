"""13-package-and-class-design §"CredentialResolver": "Returns short-lived
credential handles by connector, tenant, scope, and policy decision."
02-business-invariants INV-TG-004: "Credential values must not enter: Agent
prompt/context, Runtime checkpoint, Ticket comment, Memory document, Event
payload, Application log. Credentials may exist only transiently inside connector
invocation." — the return shape below (``CredentialHandle``) is deliberately a
short-lived reference the caller passes straight into
``ConnectorInvocationSpec.credential_binding_id``, never a secret value the
application layer, domain, or any log statement could hold or print.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True, slots=True)
class CredentialHandle:
    """01-domain-model §"CredentialBinding": "Credential values are not stored in
    business tables. Only vault references, scopes, rotation metadata,
    lastUsedAt, and audit references are stored." No field here can ever carry a
    secret value — enforced by the shape, not by convention.
    """

    credential_binding_id: str
    vault_ref: str
    scopes: tuple[str, ...]


class CredentialPort(Protocol):
    def resolve(self, connector_id: object, secret_requirements: tuple[str, ...], risk_decision_id: str) -> CredentialHandle:
        """Returns a short-lived handle. The connector adapter is the only code
        allowed to dereference ``vault_ref`` into an actual secret, and only for
        the duration of one ``ConnectorPort.invoke()`` call.
        """
        ...
