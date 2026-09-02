import { authedFetch } from "@/lib/httpClient";
import { TICKET_WORKFLOW_BASE_URL } from "@/lib/env";
import type { QueueFilters, QueueResponse } from "@/features/queue/types";

/** SPEC-SC-003: real, already-implemented `GET /api/v1/support/tickets` (SupportTicketQueryController). */
export async function getQueue(filters: QueueFilters): Promise<QueueResponse> {
  const params = new URLSearchParams();
  filters.status?.forEach((s) => params.append("status", s));
  filters.priority?.forEach((p) => params.append("priority", p));
  filters.applicationCode?.forEach((a) => params.append("applicationCode", a));
  filters.assignedTeam?.forEach((t) => params.append("assignedTeam", t));
  if (filters.assignedAgent) params.set("assignedAgent", filters.assignedAgent);
  if (filters.unassignedOnly) params.set("unassignedOnly", "true");
  filters.slaState?.forEach((s) => params.append("slaState", s));

  const query = params.toString();
  const response = await authedFetch(`${TICKET_WORKFLOW_BASE_URL}/api/v1/support/tickets${query ? `?${query}` : ""}`, { method: "GET" });
  return (await response.json()) as QueueResponse;
}
