import { create } from "zustand";

/**
 * Which top-level screen the authenticated employee is looking at. This app
 * deliberately has no route-based navigation (see AuthGate.tsx's own comment:
 * "renders purely off AuthStatus, never off a route path") — HomePage switches
 * on this store the same way AuthGate switches on AuthStatus.
 *
 * - `conversation`: the default assistant chat (ConversationView).
 * - `newTicket`: SPEC-EP-018's manual `POST /api/v1/tickets` path, promoted
 *   from a hidden agent-unavailable fallback to a first-class choice — an
 *   employee who would rather just file a ticket for a human than talk to the
 *   assistant at all.
 */
export type PortalView = "conversation" | "newTicket";

interface PortalViewState {
  view: PortalView;
  showConversation: () => void;
  showNewTicket: () => void;
}

export const usePortalViewStore = create<PortalViewState>((set) => ({
  view: "conversation",
  showConversation: () => set({ view: "conversation" }),
  showNewTicket: () => set({ view: "newTicket" }),
}));
