import { authedFetch } from "@/lib/httpClient";
import { BFF_BASE_URL, TICKET_WORKFLOW_BASE_URL } from "@/lib/env";
import { newTraceparent } from "@/lib/trace";
import { parseApiError } from "@/lib/apiError";
import type { TraceWaterfall } from "@/features/trace/types";

/**
 * SPEC-SC-014 / UC-SC-05: the latest OpenTelemetry trace id recorded against
 * a ticket, so the detail page can build a Tempo deep link without an agent
 * pasting an id on the Observability page. Real endpoint
 * `GET /api/v1/tickets/{id}/trace` (ticket-workflow-service
 * `TicketTraceController`) — a plain bearer call (unlike `fetchTraceWaterfall`
 * below, which is a session-cookie BFF call), gated by
 * `tickets:timeline:internal`. `null` means no trace-bearing audit row yet.
 */
export async function fetchTicketTraceId(ticketId: string): Promise<string | null> {
  const response = await authedFetch(`${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets/${ticketId}/trace`, { method: "GET" });
  const body = (await response.json()) as { traceId: string | null };
  return body.traceId;
}

/**
 * SPEC-SC-014: `GET /api/v1/observability/traces/{traceId}` — a real
 * session-cookie-authenticated BFF endpoint (user-access-authentication-
 * service's own `TraceWaterfallController`), NOT a bearer-token call to a
 * downstream resource server. `credentials: "include"` is the right
 * mechanism here, matching `fetchBrowserSessionToken`'s own precedent in
 * `authClient.ts` — `authedFetch` (this app's bearer-token helper) would be
 * the wrong tool, since this endpoint authenticates off the browser's own
 * `OAuth2AuthenticationToken` session, never an `Authorization` header.
 */
export async function fetchTraceWaterfall(traceId: string): Promise<TraceWaterfall> {
  const response = await fetch(`${BFF_BASE_URL}/api/v1/observability/traces/${traceId}`, {
    method: "GET",
    credentials: "include",
    headers: { Accept: "application/json", traceparent: newTraceparent() },
  });
  if (!response.ok) {
    throw await parseApiError(response);
  }
  return (await response.json()) as TraceWaterfall;
}
