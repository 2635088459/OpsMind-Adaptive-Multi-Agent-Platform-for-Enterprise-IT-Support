import { authedFetch } from "@/lib/httpClient";
import { TICKET_WORKFLOW_BASE_URL } from "@/lib/env";

/**
 * ticket-workflow-service's `SupportTicketDetailResponse` (Java record field
 * names, already camelCase). `GET /api/v1/tickets/{id}` is one physical
 * endpoint that renders the Employee *or* Support view off the trusted JWT's
 * `actor_type` — a support-console token gets this shape, which (unlike the
 * employee view) does disclose internal assignment.
 */
export interface SupportTicketDetail {
  ticketId: string;
  displayId: string;
  title: string;
  description: string;
  applicationCode: string;
  source: string;
  status: string;
  priority: string;
  requesterRef: string;
  assignment: { teamId: string | null; agentId: string | null; queue: string };
  resolutionCycle: { cycleNumber: number; status: string };
  sla: { state: string; policyId: string | null; responseDueAt: string | null; resolutionDueAt: string | null };
  createdAt: string;
  updatedAt: string;
  version: number;
}

export async function getSupportTicket(ticketId: string): Promise<SupportTicketDetail> {
  const response = await authedFetch(`${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets/${ticketId}`, { method: "GET" });
  return (await response.json()) as SupportTicketDetail;
}
