import { authedFetch, newIdempotencyKey } from "@/lib/httpClient";
import { TICKET_WORKFLOW_BASE_URL } from "@/lib/env";
import type { TicketDetail } from "@/features/ticket/types";

const BASE = `${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets`;

/** SPEC-EP-013: real, already-implemented `GET /api/v1/tickets/{id}` (PublicTicketQueryController). */
export async function getTicket(ticketId: string): Promise<TicketDetail> {
  const response = await authedFetch(`${BASE}/${ticketId}`, { method: "GET" });
  return (await response.json()) as TicketDetail;
}

/**
 * SPEC-EP-016: real `POST /api/v1/tickets/{id}/resolution-confirmation`
 * (ConfirmResolutionController) — `reasonCode`/`reason` are both genuinely
 * required by that endpoint's own request validation (`@NotBlank @Size(min=3)`),
 * unlike this spec's own text which only describes a bare yes/no click; a
 * fixed, honest reason string stands in for a yes-click since this spec
 * collects no free text of its own for the confirm path (only SPEC-EP-017's
 * reopen path does). `expectedVersion` is the real optimistic-concurrency
 * `If-Match` this endpoint requires.
 */
export async function confirmResolution(ticketId: string, expectedVersion: number): Promise<{ status: string; version: number }> {
  const response = await authedFetch(`${BASE}/${ticketId}/resolution-confirmation`, {
    method: "POST",
    idempotencyKey: newIdempotencyKey(),
    headers: { "Content-Type": "application/json", "If-Match": `"${expectedVersion}"` },
    body: JSON.stringify({ reasonCode: "REQUESTER_CONFIRMED", reason: "Confirmed as resolved by the requester via the employee portal." }),
  });
  return (await response.json()) as { status: string; version: number };
}

/**
 * SPEC-EP-017: real `POST /api/v1/tickets/{id}/reopen-request`
 * (RequesterReopenTicketController). Unlike this spec's own text ("an
 * optional free-text note"), the real request requires `reopenReason`
 * non-blank, 10–1000 chars (`@NotBlank @Size(min=10,max=1000)`) — the
 * composer enforces the same real minimum client-side rather than letting a
 * blank/too-short note reach the backend as a guaranteed validation error.
 */
export async function reopenTicket(ticketId: string, expectedVersion: number, reopenReason: string): Promise<{ status: string; version: number }> {
  const response = await authedFetch(`${BASE}/${ticketId}/reopen-request`, {
    method: "POST",
    idempotencyKey: newIdempotencyKey(),
    headers: { "Content-Type": "application/json", "If-Match": `"${expectedVersion}"` },
    body: JSON.stringify({ reopenReasonCode: "REQUESTER_REPORTED_NOT_FIXED", reopenReason }),
  });
  return (await response.json()) as { status: string; version: number };
}

/**
 * SPEC-EP-018's own fallback path: the real, already-implemented `POST
 * /api/v1/tickets` (PublicTicketController) — used directly (not through
 * agent-runtime-service's own create_ticket, which requires a working
 * conversation round trip that is, by definition, the very thing that just
 * failed). `applicationCode: OTHER`/`source: PORTAL` are honest defaults —
 * this spec's own manual-fallback path collects no category selection of
 * its own.
 */
export async function createTicketManually(title: string, description: string): Promise<{ ticketId: string; displayId: string }> {
  const response = await authedFetch(BASE, {
    method: "POST",
    idempotencyKey: newIdempotencyKey(),
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ title, description, applicationCode: "OTHER", source: "PORTAL" }),
  });
  return (await response.json()) as { ticketId: string; displayId: string };
}
