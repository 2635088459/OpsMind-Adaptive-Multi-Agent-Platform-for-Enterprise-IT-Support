/**
 * The real ticket lifecycle (`Ticket.java`'s own status machine, confirmed
 * directly — never inferred from spec prose) collapsed into the 4 stages an
 * operator actually thinks in: Triage, Assign, Start work, Resolve. Drives
 * both `TicketProgressTrail`'s own rendering and which action panel opens
 * by default on the ticket detail pane — the two were drifting out of sync
 * as separate ad-hoc checks before this was pulled into one place.
 */
export type ProgressStepKey = "triage" | "assign" | "start" | "resolve";
export type ProgressStepState = "done" | "current" | "todo";

export interface ProgressStep {
  key: ProgressStepKey;
  label: string;
  state: ProgressStepState;
}

export interface TicketProgress {
  steps: ProgressStep[];
  /** `null` once the ticket has reached a terminal status — nothing is "next" anymore. */
  current: ProgressStepKey | null;
  closed: boolean;
}

const TERMINAL_STATUSES = new Set(["RESOLVED", "CLOSED", "CANCELLED", "FAILED_FINAL"]);
const WORKING_STATUSES = new Set(["IN_PROGRESS", "WAITING_FOR_APPROVAL"]);

export function computeTicketProgress(status: string, agentId: string | null): TicketProgress {
  const closed = TERMINAL_STATUSES.has(status);
  const triageDone = closed || status !== "NEW";
  const assignDone = closed || agentId !== null;
  const startDone = closed || WORKING_STATUSES.has(status);

  const order: ProgressStepKey[] = ["triage", "assign", "start", "resolve"];
  const doneByKey: Record<ProgressStepKey, boolean> = { triage: triageDone, assign: assignDone, start: startDone, resolve: closed };
  const current = closed ? null : (order.find((key) => !doneByKey[key]) ?? "resolve");

  function stateFor(key: ProgressStepKey): ProgressStepState {
    if (key === current) return "current";
    return doneByKey[key] ? "done" : "todo";
  }

  return {
    closed,
    current,
    steps: [
      { key: "triage", label: "Triage", state: stateFor("triage") },
      { key: "assign", label: "Assign", state: stateFor("assign") },
      { key: "start", label: "Start work", state: stateFor("start") },
      { key: "resolve", label: "Resolve", state: stateFor("resolve") },
    ],
  };
}
