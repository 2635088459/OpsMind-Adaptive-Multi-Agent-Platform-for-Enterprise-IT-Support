import { create } from "zustand";
import type { ActionOutcome, MessageTurn } from "@/features/conversation/types";

export interface TranscriptEntry {
  id: string;
  author: "employee" | "agent";
  turn: MessageTurn;
}

export interface PendingAction {
  actionId: string;
  summary: string;
  riskLevel: string;
}

export interface EscalationInfo {
  ticketId: string;
  displayId: string | null;
  reason: string | null;
  assignedTeam: string | null;
}

interface ConversationState {
  conversationId: string | null;
  transcript: TranscriptEntry[];
  pendingAction: PendingAction | null;
  escalation: EscalationInfo | null;
  lastActionOutcome: ActionOutcome | null;
  /** SPEC-EP-003: the composer's own live text lives here (not component-local `useState`) so draft-preservation can read/write it without prop-drilling. */
  draftText: string;
  /** SPEC-EP-018 (BI-EP-006): the text that just failed to send — preserved so the composer never silently empties on a real backend outage. */
  lastFailedText: string | null;

  setConversationId: (id: string) => void;
  appendEmployeeMessage: (text: string) => void;
  applyAgentTurn: (turn: MessageTurn) => void;
  applyActionOutcome: (outcome: ActionOutcome) => void;
  setDraftText: (text: string) => void;
  setLastFailedText: (text: string | null) => void;
  reset: () => void;
}

/**
 * SPEC-EP-005/007/012's own shared transcript/content store — deliberately
 * separate from turnStore.ts (SPEC-EP-006), which owns only the abstract
 * state-machine node (IDLE/SENDING/...), never message content. Components
 * read both: turnStore for what to show, this store for what to show it
 * with.
 */
export const useConversationStore = create<ConversationState>((set) => ({
  conversationId: null,
  transcript: [],
  pendingAction: null,
  escalation: null,
  lastActionOutcome: null,
  draftText: "",
  lastFailedText: null,

  setConversationId: (id) => set({ conversationId: id }),
  setDraftText: (text) => set({ draftText: text }),
  setLastFailedText: (text) => set({ lastFailedText: text }),

  appendEmployeeMessage: (text) =>
    set((state) => ({
      transcript: [...state.transcript, { id: crypto.randomUUID(), author: "employee", turn: { kind: "text", text } }],
    })),

  applyAgentTurn: (turn) =>
    set((state) => {
      const entry: TranscriptEntry = { id: crypto.randomUUID(), author: "agent", turn };
      const next: Partial<ConversationState> = { transcript: [...state.transcript, entry] };
      if (turn.kind === "proposedAction") {
        next.pendingAction = { actionId: turn.actionId, summary: turn.summary, riskLevel: turn.riskLevel };
      }
      if (turn.kind === "escalation") {
        next.escalation = { ticketId: turn.ticketId, displayId: turn.displayId, reason: turn.reason, assignedTeam: turn.assignedTeam };
      }
      return next;
    }),

  applyActionOutcome: (outcome) => set({ pendingAction: null, lastActionOutcome: outcome }),

  reset: () => set({ conversationId: null, transcript: [], pendingAction: null, escalation: null, lastActionOutcome: null, draftText: "", lastFailedText: null }),
}));
