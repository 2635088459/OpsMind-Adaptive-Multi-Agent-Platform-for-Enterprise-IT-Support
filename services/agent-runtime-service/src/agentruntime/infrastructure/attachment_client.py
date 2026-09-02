"""SPEC-ARO-039's own multimodal follow-up: the real outbound HTTP client to
attachment-service (SPEC-EP-010/011's own shared attachments capability) —
GET /api/v1/attachments/{ref}/content. Mirrors HttpTicketWorkflowClient's own
shape/error posture exactly (a real httpx.Client, a dedicated
AttachmentFetchFailedException on any non-2xx/network failure), authenticating via the
same SPEC-ARO-043 outbound service identity that client's own triage_ticket() uses —
see AttachmentClientPort's own docstring for why.
"""

from __future__ import annotations

import httpx

from agentruntime.application.exceptions import AttachmentFetchFailedException
from agentruntime.application.ports_out import OutboundServiceTokenProviderPort
from agentruntime.application.records import AttachmentContent


class HttpAttachmentClient:
    def __init__(
        self, base_url: str, http_client: httpx.Client | None = None, *, token_provider: OutboundServiceTokenProviderPort | None = None,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._token_provider = token_provider
        self._http_client = http_client or httpx.Client(timeout=10.0)

    def fetch_content(self, attachment_ref: str) -> AttachmentContent:
        service_token = self._token_provider.get_token() if self._token_provider else None
        if service_token is None:
            raise AttachmentFetchFailedException(attachment_ref, "no outbound service token provider is configured")

        try:
            response = self._http_client.get(
                f"{self._base_url}/api/v1/attachments/{attachment_ref}/content",
                headers={"Authorization": f"Bearer {service_token}"},
            )
        except httpx.HTTPError as exc:
            raise AttachmentFetchFailedException(attachment_ref, f"request to attachment-service failed: {exc}") from exc

        if response.status_code != httpx.codes.OK:
            raise AttachmentFetchFailedException(attachment_ref, f"attachment-service returned status {response.status_code}")

        mime_type = response.headers.get("content-type", "application/octet-stream")
        return AttachmentContent(attachment_ref=attachment_ref, content=response.content, mime_type=mime_type)
