import { ConversationView } from "@/features/conversation/ConversationView";
import { NewTicketForm } from "@/features/ticket/NewTicketForm";
import { usePortalViewStore } from "@/store/portalViewStore";

/**
 * SPEC-EP-001 §9's own "redirect to the portal's home route" — the real
 * conversational entry point (SPEC-EP-004 onward). Switches to the manual
 * ticket form on `portalViewStore.view`, the same state-not-route pattern
 * AuthGate uses for login vs. home (this app has no route-based navigation).
 */
export function HomePage() {
  const view = usePortalViewStore((state) => state.view);
  return view === "newTicket" ? <NewTicketForm /> : <ConversationView />;
}
