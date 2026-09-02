"""SPEC-ARO-038/041 (phase-10 Conversational Intake): the real outbound HTTP client to
02-ticket-workflow — POST /api/v1/tickets (SPEC-ARO-038) and
POST /{ticketId}/triage (SPEC-ARO-041).

See TicketWorkflowClientPort's own docstring for why create_ticket() forwards the
caller's own bearer token rather than authenticating via SPEC-ARO-043's service
identity: 02-ticket-workflow's real PublicTicketController records `jwt.getSubject()`
directly as the ticket's requesterId, with no "on behalf of" mechanism — the token
must genuinely belong to the employee, not this service. triage_ticket() is the
opposite case: escalation is genuinely service-to-service (TriageTicketController
accepts any non-EMPLOYEE actor_type), so it authenticates via
OutboundServiceTokenProviderPort (SPEC-ARO-043), acquired internally rather than
passed in by the caller — SendMessageService never handles this token directly.

domain-rules (SPEC-ARO-038): "the ticket created is always real ... never fabricated
or simulated locally" and "the request requires an Idempotency-Key." Placeholder
title/description are used (see _PLACEHOLDER_TITLE's own docstring) since domain 09's
own product design collects the real issue description on the first message, not at
conversation start — a known, self-flagged gap: 02-ticket-workflow currently exposes
no endpoint to update a ticket's title/description after creation, so this placeholder
persists until either a future spec adds one or SPEC-ARO-039 finds another way to
enrich it.
"""

from __future__ import annotations

import uuid

import httpx

from agentruntime.application.exceptions import (
    TicketCreationFailedException,
    TicketTriageFailedException,
)
from agentruntime.application.ports_out import OutboundServiceTokenProviderPort
from agentruntime.application.records import CreatedTicketRef, TriagedTicketRef
from agentruntime.domain.ids import TicketCycleId, TicketId

# SPEC-ARO-038 api-contract: title/description are supplied on the first message, not
# at conversation start (matching domain 09's own UC-EP-01) — but 02-ticket-workflow's
# real CreateTicketRequest requires both non-blank. No endpoint exists today to update
# either field after creation (confirmed by reading ticket-workflow-service's own API
# surface — no PATCH/PUT on /api/v1/tickets/{id}), so this placeholder is a known,
# durable gap flagged for a future spec, not silently assumed fixed.
_PLACEHOLDER_TITLE = "New conversation"
_PLACEHOLDER_DESCRIPTION = "Started via the employee portal chat; details pending the first message."
_APPLICATION_CODE = "OTHER"
_SOURCE = "PORTAL"


class HttpTicketWorkflowClient:
    def __init__(
        self, base_url: str, http_client: httpx.Client | None = None, *, token_provider: OutboundServiceTokenProviderPort | None = None,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._token_provider = token_provider
        self._http_client = http_client or httpx.Client(timeout=10.0)

    def create_ticket(self, forwarded_bearer_token: str, idempotency_key: str) -> CreatedTicketRef:
        try:
            response = self._http_client.post(
                f"{self._base_url}/api/v1/tickets",
                json={
                    "title": _PLACEHOLDER_TITLE,
                    "description": _PLACEHOLDER_DESCRIPTION,
                    "applicationCode": _APPLICATION_CODE,
                    "source": _SOURCE,
                },
                headers={
                    "Authorization": f"Bearer {forwarded_bearer_token}",
                    "Idempotency-Key": idempotency_key,
                    "Content-Type": "application/json",
                },
            )
        except httpx.HTTPError as exc:
            raise TicketCreationFailedException(f"request to ticket-workflow failed: {exc}") from exc

        if response.status_code != httpx.codes.CREATED:
            raise TicketCreationFailedException(f"ticket-workflow returned status {response.status_code}")

        try:
            body = response.json()
            ticket_id = uuid.UUID(body["ticketId"])
            resolution_cycle_id = uuid.UUID(body["resolutionCycleId"])
            version = int(body["version"])
            display_id = body["displayId"]
        except (ValueError, KeyError) as exc:
            raise TicketCreationFailedException(f"ticket-workflow response was malformed: {exc}") from exc

        return CreatedTicketRef(
            ticket_id=TicketId(ticket_id), ticket_cycle_id=TicketCycleId(resolution_cycle_id), version=version,
            display_id=display_id,
        )

    def triage_ticket(
        self, ticket_id: TicketId, current_version: int, category_id: str, support_queue_id: str, priority: str,
        reason: str, idempotency_key: str,
    ) -> TriagedTicketRef:
        service_token = self._token_provider.get_token() if self._token_provider else None
        if service_token is None:
            raise TicketTriageFailedException("no outbound service token provider is configured")

        try:
            response = self._http_client.post(
                f"{self._base_url}/api/v1/tickets/{ticket_id}/triage",
                json={"categoryId": category_id, "priority": priority, "supportQueueId": support_queue_id, "reason": reason},
                headers={
                    "Authorization": f"Bearer {service_token}",
                    "Idempotency-Key": idempotency_key,
                    "If-Match": f'"{current_version}"',
                    "Content-Type": "application/json",
                },
            )
        except httpx.HTTPError as exc:
            raise TicketTriageFailedException(f"request to ticket-workflow failed: {exc}") from exc

        if response.status_code != httpx.codes.OK:
            raise TicketTriageFailedException(f"ticket-workflow returned status {response.status_code}")

        try:
            version = int(response.json()["version"])
        except (ValueError, KeyError) as exc:
            raise TicketTriageFailedException(f"ticket-workflow response was malformed: {exc}") from exc

        return TriagedTicketRef(version=version)
