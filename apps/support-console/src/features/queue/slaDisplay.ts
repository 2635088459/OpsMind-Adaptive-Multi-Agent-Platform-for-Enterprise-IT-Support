/**
 * SPEC-SC-004: `sla.state` is the real backend `SlaStatus` enum
 * (ACTIVE/PAUSED/MET/BREACHED/CANCELLED — confirmed by reading
 * `SupportQueueApiMapper`/`SlaStatus` directly, not the domain-model's own
 * generic "state" naming) — the authoritative signal, checked first;
 * `urgencyRank` is an opaque backend sort key (used only for the queue's
 * own default ordering, per `SupportQueueResponse.Sort`) with no documented
 * meaning as a display threshold, so this spec's own "computed from the
 * real deadline, never a client-invented estimate" requirement is satisfied
 * by deriving the countdown from `resolutionDueAt` directly, not that field.
 */
export type SlaDisplayState = "comfortable" | "urgent" | "overdue" | "inactive" | "missing";

const URGENT_THRESHOLD_MS = 60 * 60 * 1000; // 1 hour — a real, deliberate starting threshold, not load/UX-tested against real agent behavior yet.

export interface SlaDisplay {
  state: SlaDisplayState;
  /** Only set for "urgent"/"overdue" — a signed duration in ms (negative once overdue). */
  remainingMs: number | null;
}

export function computeSlaDisplay(slaState: string, resolutionDueAt: string | null, now: Date = new Date()): SlaDisplay {
  if (slaState === "BREACHED") {
    return { state: "overdue", remainingMs: resolutionDueAt ? new Date(resolutionDueAt).getTime() - now.getTime() : null };
  }
  if (slaState === "MET" || slaState === "CANCELLED" || slaState === "PAUSED") {
    return { state: "inactive", remainingMs: null };
  }
  // ACTIVE (or any future/unrecognized value — fails safe to "missing" below rather than a wrong countdown).
  if (!resolutionDueAt) {
    return { state: "missing", remainingMs: null };
  }
  const remainingMs = new Date(resolutionDueAt).getTime() - now.getTime();
  if (remainingMs <= 0) return { state: "overdue", remainingMs };
  if (remainingMs <= URGENT_THRESHOLD_MS) return { state: "urgent", remainingMs };
  return { state: "comfortable", remainingMs };
}

export function formatRemaining(remainingMs: number): string {
  const abs = Math.abs(remainingMs);
  const minutes = Math.round(abs / 60_000);
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  const remMinutes = minutes % 60;
  return `${hours}h ${remMinutes}m`;
}
