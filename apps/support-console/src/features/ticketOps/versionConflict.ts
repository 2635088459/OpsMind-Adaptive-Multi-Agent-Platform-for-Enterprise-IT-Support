import { ApiError } from "@/lib/apiError";

/**
 * SPEC-SC-013: ticket-workflow-service's one real optimistic-concurrency
 * contract — every mutating support endpoint this console calls (triage,
 * assign/reassign/unassign, status-transition, resolution) requires an
 * `If-Match: <version>` header and rejects a stale one with a real
 * `412 PRECONDITION_FAILED` / `VERSION_CONFLICT` body carrying
 * `details.currentVersion` (confirmed directly in each controller and
 * `GlobalRestExceptionHandler#handleTicketVersionConflict`) — never a 409,
 * despite this spec's own §16 prose using "409-style" loosely.
 */
export function isVersionConflict(error: unknown): error is ApiError {
  return error instanceof ApiError && error.status === 412 && error.code === "VERSION_CONFLICT";
}

/** The ticket's real current version, as the backend's own conflict body reports it (SPEC-TW-007 AC-10) — `null` only if the shape ever changes underneath this client. */
export function currentVersionFrom(error: ApiError): number | null {
  const value = error.details?.currentVersion;
  return typeof value === "number" ? value : null;
}
