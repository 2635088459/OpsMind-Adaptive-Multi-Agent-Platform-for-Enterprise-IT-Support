/**
 * SPEC-EP-006: the pure transition table for `03-state-machine` §3.1, decoupled
 * from Zustand/React entirely — a plain function over a plain enum, so it can
 * be unit-tested exhaustively (every legal edge, every illegal one rejected)
 * without mounting any store or component.
 */
export type TurnState =
  | "IDLE"
  | "SENDING"
  | "AWAITING_AGENT"
  | "AWAITING_CONFIRMATION"
  | "ACTION_EXECUTING"
  | "ESCALATED"
  | "AGENT_UNAVAILABLE"
  // SPEC-EP-015: a conversation resumed from a real TERMINAL backend
  // WorkflowState (COMPLETED / FAILED / CANCELLED — an escalation completes
  // the workflow instance, per SPEC-ARO-041). Like `ESCALATED` it has no
  // message-sending edge — the composer must NOT be re-enabled for a
  // conversation the backend will only ever answer with a 409. Its one edge
  // is the explicit "start a new conversation" affordance.
  | "RESUMED_CLOSED";

export type TurnEvent =
  | "sendMessage"
  | "requestSent"
  | "receivedText"
  | "receivedProposedAction"
  | "receivedEscalation"
  | "confirmClicked"
  | "declineClicked"
  | "actionOutcomeReceived"
  | "agentUnavailable"
  | "retry"
  | "startNewConversation";

export class IllegalTurnTransitionError extends Error {
  constructor(state: TurnState, event: TurnEvent) {
    super(`Illegal turn transition: '${event}' is not valid from state '${state}'`);
    this.name = "IllegalTurnTransitionError";
  }
}

/**
 * Reconciles two of this domain's own specs that, read in isolation, seem to
 * disagree: SPEC-EP-005 §10 has an agent response land in `ESCALATED` as one
 * of three terminal shapes; SPEC-EP-012 §10 separately says "turn state
 * transitions to IDLE after an escalation message ... no further self-service
 * action is offered." Reconciled here as: `ESCALATED` IS the state (so
 * SPEC-EP-012's own `EscalationNotice` can render distinctly from a plain
 * `IDLE` composer) and it is terminal for this conversation — there is no
 * edge back to `IDLE`, matching domain 03's own real backend behavior
 * (SPEC-ARO-041: escalation completes the workflow instance; no further
 * message can ever be sent on it). "No further self-service action" is
 * satisfied by `ESCALATED` having no outward edges at all, not by a
 * transition to `IDLE` that would misleadingly re-enable the composer.
 */
const TRANSITIONS: Record<TurnState, Partial<Record<TurnEvent, TurnState>>> = {
  IDLE: { sendMessage: "SENDING" },
  SENDING: { requestSent: "AWAITING_AGENT" },
  AWAITING_AGENT: {
    receivedText: "IDLE",
    receivedProposedAction: "AWAITING_CONFIRMATION",
    receivedEscalation: "ESCALATED",
    agentUnavailable: "AGENT_UNAVAILABLE",
  },
  // BI-EP-003, enforced structurally (SPEC-EP-006 §11): the only two edges out
  // of AWAITING_CONFIRMATION are an explicit confirm or an explicit decline —
  // there is no path to ACTION_EXECUTING (a side-effecting state) that
  // doesn't pass through `confirmClicked`.
  AWAITING_CONFIRMATION: { confirmClicked: "ACTION_EXECUTING", declineClicked: "IDLE" },
  ACTION_EXECUTING: { actionOutcomeReceived: "IDLE" },
  ESCALATED: {},
  AGENT_UNAVAILABLE: { retry: "SENDING" },
  // SPEC-EP-015: the only way out is the employee explicitly starting fresh
  // (the ConversationView banner's own button), which also `reset()`s the
  // conversation store so the next message opens a brand-new conversation.
  RESUMED_CLOSED: { startNewConversation: "IDLE" },
};

export function transition(state: TurnState, event: TurnEvent): TurnState {
  const next = TRANSITIONS[state][event];
  if (!next) {
    throw new IllegalTurnTransitionError(state, event);
  }
  return next;
}
