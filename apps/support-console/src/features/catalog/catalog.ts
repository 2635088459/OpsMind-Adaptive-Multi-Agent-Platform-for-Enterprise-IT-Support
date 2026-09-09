/**
 * A small, curated catalog of the real ticket-workflow reference data an
 * operator needs to fill in a triage / assignment form — so the console shows
 * "Network Support Queue", not a bare UUID text box nobody can guess.
 *
 * Why it's a static list and not a fetch: ticket-workflow-service exposes NO
 * catalog-read endpoint for categories, support queues, or support agents
 * anywhere (checked directly against every *Controller.java) — a long-carried
 * platform gap, not an oversight here. These four rows are the exact,
 * ON CONFLICT DO NOTHING seed values from migrations V045
 * (seed_default_escalation_routing) and V046 (seed_support_agents_and_
 * memberships), verified 1:1 against the running database. Every form that
 * uses these also keeps an "enter a different ID" escape hatch, so anything
 * that was possible with the old raw text field is still possible.
 */

export interface CatalogEntry {
  /** The real backend UUID sent on the wire. */
  id: string;
  /** Human label shown in the picker. */
  label: string;
  /** A short secondary hint (code / team / role) shown under the picker. */
  hint?: string;
}

export const TICKET_CATEGORIES: CatalogEntry[] = [
  { id: "11111111-1111-1111-1111-111111111111", label: "Network", hint: "code NETWORK" },
];

export const SUPPORT_QUEUES: CatalogEntry[] = [
  { id: "33333333-3333-3333-3333-333333333333", label: "Network Support Queue", hint: "team network-support-team" },
];

export const SUPPORT_AGENTS: CatalogEntry[] = [
  { id: "e85c3314-64e6-48bb-b494-26d75fee189d", label: "Support Agent", hint: "IT_SUPPORT" },
  { id: "bb474907-54cb-4080-a8f3-4d00ba83741d", label: "Support Admin", hint: "IT_ADMIN" },
];

/** The catalog entry whose id matches `value`, or `undefined` for a custom / empty id. */
export function findEntry(entries: CatalogEntry[], value: string): CatalogEntry | undefined {
  return entries.find((entry) => entry.id === value);
}

/**
 * A human label for an assignee/agent id. ticket-workflow returns only the raw
 * agent UUID on the queue and detail responses (no display-name field), so the
 * console resolves it against the seeded support agents and otherwise falls
 * back to a short prefix of the id rather than showing a 36-char UUID.
 */
export function agentLabel(agentId: string | null | undefined): string {
  if (!agentId) return "Unassigned";
  const known = findEntry(SUPPORT_AGENTS, agentId);
  if (known) return known.label;
  return `${agentId.slice(0, 8)}…`;
}
