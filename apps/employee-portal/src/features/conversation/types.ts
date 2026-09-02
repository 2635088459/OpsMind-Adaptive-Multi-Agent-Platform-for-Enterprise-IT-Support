/**
 * Idiomatic camelCase domain types this app's own components/hooks work
 * with — mapped in api.ts from agent-runtime-service's real wire shape,
 * which is snake_case (confirmed by reading interfaces/conversation/
 * schemas.py directly: no Pydantic alias_generator is configured there,
 * unlike this app's own assumption when SPEC-EP-004/005 were first
 * written against a generic camelCase mock).
 */

export interface Conversation {
  conversationId: string;
  startedAt: string;
}

/** The 3 discriminated shapes SPEC-ARO-039 returns for one message turn. */
export type MessageTurn =
  | { kind: "text"; text: string }
  | { kind: "proposedAction"; actionId: string; summary: string; riskLevel: string }
  | { kind: "escalation"; ticketId: string; displayId: string | null; reason: string | null; assignedTeam: string | null };

/** SPEC-ARO-040's own real outcome vocabulary — never a client-invented 5th value. */
export type ActionOutcome = "done" | "still-processing" | "awaiting-approval" | "declined";

export interface ConversationDetail {
  conversationId: string;
  /** The real backend WorkflowState name (e.g. RUNNING, COMPLETED, WAITING_FOR_APPROVAL) — deliberately not narrowed further here; see api.ts's own mapping-to-TurnState doc for why. */
  state: string;
  startedAt: string;
  updatedAt: string;
}
