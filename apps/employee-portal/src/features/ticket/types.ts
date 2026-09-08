/**
 * Matches ticket-workflow-service's real `EmployeeTicketDetailResponse`
 * directly (Java record field names, already camelCase — unlike agent-
 * runtime-service's own Python/snake_case wire shape, no mapping needed
 * here beyond the type declaration itself). No `assignee` field exists on
 * the employee-facing view at all (confirmed by reading that response class
 * directly) — SPEC-EP-013's own text mentions one, but the real contract
 * deliberately never discloses internal assignment to the employee.
 */
/**
 * ticket-workflow-service's real `ApplicationCode` enum (domain/value/
 * ApplicationCode.java) — the only category values `POST /api/v1/tickets`
 * accepts. `source` is always `PORTAL` from this app (real `TicketSource`
 * enum), so it is not modelled as a user choice.
 */
export type TicketApplicationCode = "HOUSING_PORTAL" | "EMAIL" | "VPN" | "OTHER";

export interface CreateTicketInput {
  title: string;
  description: string;
  applicationCode: TicketApplicationCode;
}

/**
 * The real `CreateTicketResponse` record (ticket/api/publicapi/
 * CreateTicketResponse.java), field names verbatim. `resolutionCycleId` is
 * only meaningful to a synchronous machine caller (agent-runtime-service) —
 * this app reads `ticketId`/`displayId` to hand the employee a reference and
 * open the status panel.
 */
export interface CreateTicketResult {
  ticketId: string;
  displayId: string;
  status: string;
  createdAt: string;
  version: number;
  resolutionCycleId: string;
}

export interface TicketDetail {
  ticketId: string;
  displayId: string;
  title: string;
  description: string;
  applicationCode: string;
  source: string;
  status: string;
  priority: string;
  createdAt: string;
  updatedAt: string;
  version: number;
  sla: {
    state: string;
    responseDueAt: string | null;
    resolutionDueAt: string | null;
  };
  links: {
    self: string;
    timeline: string;
    messages: string;
  };
}
