/**
 * SPEC-EP-003 (BI-EP-006): `localStorage`, not IndexedDB — `08-transaction-
 * and-outbox` §3's own requirement is a SYNCHRONOUS write, since a 401 can
 * arrive moments before the tab itself is torn down by a real re-login
 * redirect; an async IndexedDB write could lose the race. `07-data-model`
 * §2.2's own key shape, subject-prefixed per §4 so a shared device/browser
 * profile never leaks one employee's draft into another's session.
 */
function draftKey(subject: string, conversationId: string): string {
  return `draft:${subject}:${conversationId}`;
}

/**
 * §16: a write failure (quota exceeded, private browsing) is an acceptable
 * degradation, never masked as success and never thrown into the caller's
 * own control flow (the re-login prompt must still show regardless).
 */
export function saveDraft(subject: string, conversationId: string, text: string): void {
  if (!text) return;
  try {
    localStorage.setItem(draftKey(subject, conversationId), text);
  } catch {
    // Acceptable degradation (§16) — the draft is lost, not the re-login prompt.
  }
}

export function loadDraft(subject: string, conversationId: string): string | null {
  try {
    return localStorage.getItem(draftKey(subject, conversationId));
  } catch {
    return null;
  }
}

export function clearDraft(subject: string, conversationId: string): void {
  try {
    localStorage.removeItem(draftKey(subject, conversationId));
  } catch {
    // Nothing else to do — a leftover draft key is harmless, not a correctness issue.
  }
}
