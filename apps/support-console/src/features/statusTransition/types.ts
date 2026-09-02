/**
 * Mirrors the real backend `TicketStatus` enum. Only `IN_PROGRESS` and
 * `WAITING_FOR_APPROVAL` are reachable through the generic transition
 * endpoint this spec targets (`TransitionTicketStatusController` narrows
 * its own HTTP surface to exactly these two — confirmed directly in that
 * controller, not inferred from spec prose, which named `RESOLVED` as an
 * example that the real backend does NOT accept here). `RESOLVED` is
 * reached only through the separate, dedicated resolution endpoint below.
 */
export type TransitionTarget = "IN_PROGRESS" | "WAITING_FOR_APPROVAL";

/** Mirrors `TransitionTicketStatusRequest`. */
export interface TransitionInput {
  targetStatus: TransitionTarget;
  reason: string;
  approvalReference?: string;
}

/** Mirrors `TransitionTicketStatusResponse`. */
export interface TransitionTicketStatusResponse {
  ticketId: string;
  previousStatus: string;
  status: string;
  reason: string;
  waitingForRequesterSince: string | null;
  approvalReference: string | null;
  transitionedAt: string;
  version: number;
}

/** Mirrors `ResolutionCode` — the real controlled vocabulary, not invented client-side. */
export type ResolutionCode = "FIXED" | "WORKAROUND_PROVIDED" | "DUPLICATE" | "REQUEST_FULFILLED" | "NOT_REPRODUCIBLE" | "USER_ERROR" | "NO_ACTION_REQUIRED";

/** Mirrors `ResolveTicketRequest`. */
export interface ResolveInput {
  resolutionCode: ResolutionCode;
  resolutionSummary: string;
}

/** Mirrors `ResolveTicketResponse`. */
export interface ResolveTicketResponse {
  ticketId: string;
  previousStatus: string;
  status: string;
  resolutionCode: string;
  resolutionSummary: string;
  resolvedBy: string;
  resolvedAt: string;
  resolutionCycleId: string;
  autoCloseDueAt: string | null;
  version: number;
}
