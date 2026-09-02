import { describe, it, expect } from "vitest";
import { IllegalTurnTransitionError, transition, type TurnEvent, type TurnState } from "@/features/conversation/turnState";

const ALL_STATES: TurnState[] = [
  "IDLE",
  "SENDING",
  "AWAITING_AGENT",
  "AWAITING_CONFIRMATION",
  "ACTION_EXECUTING",
  "ESCALATED",
  "AGENT_UNAVAILABLE",
];
const ALL_EVENTS: TurnEvent[] = [
  "sendMessage",
  "requestSent",
  "receivedText",
  "receivedProposedAction",
  "receivedEscalation",
  "confirmClicked",
  "declineClicked",
  "actionOutcomeReceived",
  "agentUnavailable",
  "retry",
];

describe("turn state machine — every legal edge in 03-state-machine §3.1", () => {
  it.each([
    ["IDLE", "sendMessage", "SENDING"],
    ["SENDING", "requestSent", "AWAITING_AGENT"],
    ["AWAITING_AGENT", "receivedText", "IDLE"],
    ["AWAITING_AGENT", "receivedProposedAction", "AWAITING_CONFIRMATION"],
    ["AWAITING_AGENT", "receivedEscalation", "ESCALATED"],
    ["AWAITING_AGENT", "agentUnavailable", "AGENT_UNAVAILABLE"],
    ["AWAITING_CONFIRMATION", "confirmClicked", "ACTION_EXECUTING"],
    ["AWAITING_CONFIRMATION", "declineClicked", "IDLE"],
    ["ACTION_EXECUTING", "actionOutcomeReceived", "IDLE"],
    ["AGENT_UNAVAILABLE", "retry", "SENDING"],
  ] satisfies Array<[TurnState, TurnEvent, TurnState]>)("%s + %s -> %s", (from, event, to) => {
    expect(transition(from, event)).toBe(to);
  });

  it("rejects every event not explicitly declared legal from each state", () => {
    const legalPairs = new Set([
      "IDLE:sendMessage",
      "SENDING:requestSent",
      "AWAITING_AGENT:receivedText",
      "AWAITING_AGENT:receivedProposedAction",
      "AWAITING_AGENT:receivedEscalation",
      "AWAITING_AGENT:agentUnavailable",
      "AWAITING_CONFIRMATION:confirmClicked",
      "AWAITING_CONFIRMATION:declineClicked",
      "ACTION_EXECUTING:actionOutcomeReceived",
      "AGENT_UNAVAILABLE:retry",
    ]);

    let illegalPairsChecked = 0;
    for (const state of ALL_STATES) {
      for (const event of ALL_EVENTS) {
        if (legalPairs.has(`${state}:${event}`)) continue;
        illegalPairsChecked += 1;
        expect(() => transition(state, event)).toThrow(IllegalTurnTransitionError);
      }
    }
    // Sanity check that this test actually exercised a meaningful number of
    // illegal pairs, not an accidentally-empty double loop.
    expect(illegalPairsChecked).toBeGreaterThan(50);
  });

  it("BI-EP-003: AWAITING_CONFIRMATION has no path to a side-effecting state other than an explicit confirm", () => {
    expect(() => transition("AWAITING_CONFIRMATION", "actionOutcomeReceived")).toThrow(IllegalTurnTransitionError);
    expect(() => transition("AWAITING_CONFIRMATION", "sendMessage")).toThrow(IllegalTurnTransitionError);
  });

  it("ESCALATED is terminal — no event moves out of it", () => {
    for (const event of ALL_EVENTS) {
      expect(() => transition("ESCALATED", event)).toThrow(IllegalTurnTransitionError);
    }
  });
});
