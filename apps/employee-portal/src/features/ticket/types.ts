/**
 * Matches ticket-workflow-service's real `EmployeeTicketDetailResponse`
 * directly (Java record field names, already camelCase — unlike agent-
 * runtime-service's own Python/snake_case wire shape, no mapping needed
 * here beyond the type declaration itself). No `assignee` field exists on
 * the employee-facing view at all (confirmed by reading that response class
 * directly) — SPEC-EP-013's own text mentions one, but the real contract
 * deliberately never discloses internal assignment to the employee.
 */
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
