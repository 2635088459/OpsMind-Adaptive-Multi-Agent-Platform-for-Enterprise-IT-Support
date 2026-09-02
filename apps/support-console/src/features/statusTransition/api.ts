import { authedFetch } from "@/lib/httpClient";
import { TICKET_WORKFLOW_BASE_URL } from "@/lib/env";
import type { ResolveInput, ResolveTicketResponse, TransitionInput, TransitionTicketStatusResponse } from "@/features/statusTransition/types";

/** SPEC-SC-012: real, already-implemented `POST /api/v1/tickets/{ticketId}/status-transitions` (TransitionTicketStatusController). Same `If-Match`/`Idempotency-Key` mechanics as triage/assignment (SPEC-SC-013). */
export async function transitionTicketStatus(
  ticketId: string,
  expectedVersion: number,
  idempotencyKey: string,
  input: TransitionInput,
): Promise<TransitionTicketStatusResponse> {
  const response = await authedFetch(`${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets/${ticketId}/status-transitions`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "If-Match": String(expectedVersion) },
    idempotencyKey,
    body: JSON.stringify(input),
  });
  return (await response.json()) as TransitionTicketStatusResponse;
}

/** SPEC-SC-012: real, already-implemented `POST /api/v1/tickets/{ticketId}/resolution` (ResolveTicketController) — the ONLY path to `RESOLVED`, distinct from the generic transition endpoint above. */
export async function resolveTicket(
  ticketId: string,
  expectedVersion: number,
  idempotencyKey: string,
  input: ResolveInput,
): Promise<ResolveTicketResponse> {
  const response = await authedFetch(`${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets/${ticketId}/resolution`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "If-Match": String(expectedVersion) },
    idempotencyKey,
    body: JSON.stringify(input),
  });
  return (await response.json()) as ResolveTicketResponse;
}
