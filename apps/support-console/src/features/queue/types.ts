/**
 * Matches ticket-workflow-service's real `SupportQueueResponse`/
 * `SupportTicketSummaryResponse` directly (Java record field names, already
 * camelCase — confirmed by reading `SupportTicketQueryController` and its
 * response DTOs directly, not assumed from this domain's own spec prose).
 */
export interface QueueRow {
  ticketId: string;
  displayId: string;
  title: string;
  applicationCode: string;
  status: string;
  priority: string;
  requesterRef: string;
  assignment: {
    teamId: string | null;
    agentId: string | null;
    unassigned: boolean;
  };
  sla: {
    state: string;
    responseDueAt: string | null;
    resolutionDueAt: string | null;
    urgencyRank: number;
  };
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface QueueResponse {
  items: QueueRow[];
  page: {
    limit: number;
    hasMore: boolean;
    nextCursor: string | null;
    evaluationTime: string;
    consistency: string;
  };
}

export interface QueueFilters {
  status?: string[];
  priority?: string[];
  applicationCode?: string[];
  assignedTeam?: string[];
  assignedAgent?: string;
  unassignedOnly?: boolean;
  slaState?: string[];
}
