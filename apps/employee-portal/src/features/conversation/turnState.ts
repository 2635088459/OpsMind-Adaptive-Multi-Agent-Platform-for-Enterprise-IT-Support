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
  | "AGENT_UNAVAILABLE";

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
  | "retry";

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
};

export function transition(state: TurnState, event: TurnEvent): TurnState {
  const next = TRANSITIONS[state][event];
  if (!next) {
    throw new IllegalTurnTransitionError(state, event);
  }
  return next;
}
