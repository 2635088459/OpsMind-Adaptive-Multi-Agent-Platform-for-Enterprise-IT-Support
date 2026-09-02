import { authedFetch } from "@/lib/httpClient";
import { TICKET_WORKFLOW_BASE_URL } from "@/lib/env";
import type { TriageInput, TriageTicketResponse } from "@/features/triage/types";

/**
 * SPEC-SC-010: real, already-implemented `POST /api/v1/tickets/{ticketId}/
 * triage` (TriageTicketController) — the identical endpoint domain 03's
 * SPEC-ARO-041 escalation path calls. Requires the real `If-Match`
 * optimistic-concurrency header (SPEC-SC-013) and a fresh `Idempotency-Key`
 * per attempt.
 */
export async function triageTicket(
  ticketId: string,
  expectedVersion: number,
  idempotencyKey: string,
  input: TriageInput,
): Promise<TriageTicketResponse> {
  const response = await authedFetch(`${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets/${ticketId}/triage`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "If-Match": String(expectedVersion) },
    idempotencyKey,
    body: JSON.stringify(input),
  });
  return (await response.json()) as TriageTicketResponse;
}
