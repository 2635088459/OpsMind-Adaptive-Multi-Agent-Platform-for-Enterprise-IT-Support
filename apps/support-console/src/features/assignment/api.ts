import { authedFetch } from "@/lib/httpClient";
import { TICKET_WORKFLOW_BASE_URL } from "@/lib/env";
import type { AssignInput, TicketAssignmentResponse, UnassignInput } from "@/features/assignment/types";

/**
 * SPEC-SC-011: real, already-implemented `POST /api/v1/tickets/{ticketId}/
 * {assign,reassign,unassign}` (TicketAssignmentController). All 3 share the
 * same `If-Match`/`Idempotency-Key` mechanics as triage (SPEC-SC-013).
 */
export async function assignTicket(ticketId: string, expectedVersion: number, idempotencyKey: string, input: AssignInput): Promise<TicketAssignmentResponse> {
  return post(`${ticketId}/assign`, expectedVersion, idempotencyKey, input);
}

export async function reassignTicket(ticketId: string, expectedVersion: number, idempotencyKey: string, input: AssignInput): Promise<TicketAssignmentResponse> {
  return post(`${ticketId}/reassign`, expectedVersion, idempotencyKey, input);
}

export async function unassignTicket(ticketId: string, expectedVersion: number, idempotencyKey: string, input: UnassignInput): Promise<TicketAssignmentResponse> {
  return post(`${ticketId}/unassign`, expectedVersion, idempotencyKey, input);
}

async function post(path: string, expectedVersion: number, idempotencyKey: string, body: unknown): Promise<TicketAssignmentResponse> {
  const response = await authedFetch(`${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets/${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "If-Match": String(expectedVersion) },
    idempotencyKey,
    body: JSON.stringify(body),
  });
  return (await response.json()) as TicketAssignmentResponse;
}
