import { ConversationView } from "@/features/conversation/ConversationView";

/**
 * SPEC-EP-001 §9's own "redirect to the portal's home route" — now the real
 * conversational entry point (SPEC-EP-004 onward), replacing this domain's
 * earlier bare "Welcome" placeholder.
 */
export function HomePage() {
  return <ConversationView />;
}
