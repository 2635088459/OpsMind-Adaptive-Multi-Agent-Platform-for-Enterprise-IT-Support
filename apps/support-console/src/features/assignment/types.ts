/** Mirrors `AssignTicketRequest` — shared by assign and reassign (identical shape). */
export interface AssignInput {
  assigneeId: string;
  reason: string;
}

/** Mirrors `UnassignTicketRequest`. */
export interface UnassignInput {
  reason: string;
}

/** Mirrors `TicketAssignmentResponse` — `assignee`/`assignedAt` are both real `null` after a successful unassign, not omitted. */
export interface TicketAssignmentResponse {
  ticketId: string;
  status: string;
  assignee: { id: string; displayName: string } | null;
  assignedAt: string | null;
  version: number;
}
