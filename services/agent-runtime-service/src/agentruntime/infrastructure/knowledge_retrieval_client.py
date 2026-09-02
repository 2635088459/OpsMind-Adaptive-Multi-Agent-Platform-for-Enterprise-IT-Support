"""SPEC-ARO-039 (phase-10 Conversational Intake): the real outbound HTTP client to
04-memory-knowledge's own POST /internal/memory/v1/search — an internal,
service-to-service route with no per-request auth dependency of its own (confirmed by
reading that router directly), so no token is attached here, unlike
TicketWorkflowClientPort's own calls.

domain-rules: "a retrieval failure degrades to a plainer answer or an escalation,
never a hallucinated citation" — every failure path here returns an empty list rather
than raising, so a knowledge-service outage never blocks a message turn outright.
"""

from __future__ import annotations

import logging

import httpx

from agentruntime.application.records import KnowledgeSnippet
from agentruntime.domain.ids import WorkflowInstanceId

logger = logging.getLogger(__name__)

# SPEC-EI/MK conventions this service reuses rather than invents: "default" is the
# established placeholder tenant (mirrors evaluation-improvement-service's own
# tenant_id() default), "INTERNAL" is memory-knowledge-service's own real default
# classification (confirmed in domain/memory.py/domain/knowledge_document.py).
_TENANT = "default"
_ROLE = "EMPLOYEE"
_CLASSIFICATION = "INTERNAL"
_MAX_RESULTS = 5


class HttpKnowledgeRetrievalClient:
    def __init__(self, base_url: str, http_client: httpx.Client | None = None) -> None:
        self._base_url = base_url.rstrip("/")
        self._http_client = http_client or httpx.Client(timeout=10.0)

    def search(self, query: str, workflow_instance_id: WorkflowInstanceId, requester_subject: str) -> list[KnowledgeSnippet]:
        try:
            response = self._http_client.post(
                f"{self._base_url}/internal/memory/v1/search",
                json={
                    "query": query, "requester_type": "EMPLOYEE", "requester_id": requester_subject,
                    "access_scope": {"tenant": _TENANT, "role": _ROLE, "classification": _CLASSIFICATION},
                    "correlation_id": str(workflow_instance_id),
                    "workflow_instance_id": str(workflow_instance_id),
                    "filters": {"max_results": _MAX_RESULTS, "include_graph_paths": False},
                },
            )
        except httpx.HTTPError as exc:
            logger.warning("knowledge retrieval request failed, degrading to no snippets: %s", exc)
            return []

        if response.status_code != httpx.codes.OK:
            logger.warning("knowledge retrieval returned status %s, degrading to no snippets", response.status_code)
            return []

        try:
            body = response.json()
            return [
                KnowledgeSnippet(source_id=item["source_id"], snippet=item["snippet"], score=item["score"])
                for item in body["results"]
            ]
        except (ValueError, KeyError, TypeError) as exc:
            logger.warning("knowledge retrieval response was malformed, degrading to no snippets: %s", exc)
            return []
