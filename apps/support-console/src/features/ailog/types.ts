/** SPEC-SC-006: one unified, chronologically-ordered entry, tagged by its real source for traceability (§9). */
export interface AiLogEntry {
  id: string;
  source: "timeline" | "governance-audit" | "tool-request";
  occurredAt: string;
  summary: string;
}

export type SourceName = "timeline" | "governanceAudit" | "toolRequest";

export type SourceStatus =
  | { kind: "ok" }
  | { kind: "unavailable" } // SPEC-SC-007: a real outage/other failure — retry may help.
  | { kind: "forbidden" }; // SPEC-SC-019: a real 403 — retry cannot help, distinct copy, no retry button.
