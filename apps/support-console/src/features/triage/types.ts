/**
 * Mirrors `TriageTicketRequest`. `categoryId`/`subcategoryId`/`supportQueueId`
 * are real backend UUIDs with no catalog-read endpoint anywhere in this
 * platform (confirmed — see project memory) — this form honestly collects
 * them as raw ID text rather than fabricating a picker backed by data that
 * doesn't exist.
 */
export interface TriageInput {
  categoryId: string;
  subcategoryId?: string;
  priority: string;
  supportQueueId: string;
  reason: string;
}

/** Mirrors `TriageTicketResponse`. */
export interface TriageTicketResponse {
  ticketId: string;
  status: string;
  categoryId: string;
  subcategoryId: string | null;
  priority: string;
  supportQueueId: string;
  triagedBy: string;
  triagedAt: string;
  version: number;
}
