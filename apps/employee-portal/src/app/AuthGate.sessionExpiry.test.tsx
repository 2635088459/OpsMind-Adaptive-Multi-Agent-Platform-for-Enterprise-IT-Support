import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/renderWithProviders";

vi.mock("@/lib/authClient", () => ({
  fetchBrowserSessionToken: vi.fn(),
  beginLogin: vi.fn(),
}));

import { fetchBrowserSessionToken } from "@/lib/authClient";
import { useAuthStore } from "@/store/authStore";
import { useConversationStore } from "@/features/conversation/conversationStore";
import { loadDraft } from "@/features/session/draftPreservation";
import { AuthGate } from "@/app/AuthGate";

/** A structurally-valid unsigned JWT so decodeJwtPayload can read a real `sub` — this app never verifies the signature client-side (jwt.ts's own doc). */
function fakeJwt(claims: Record<string, unknown>): string {
  const toBase64Url = (obj: object) => btoa(JSON.stringify(obj)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  return `${toBase64Url({ alg: "none" })}.${toBase64Url(claims)}.sig`;
}

/**
 * SPEC-EP-003's own real acceptance scenario: a message typed but not yet
 * sent survives a real session-expiry transition — driven here through the
 * actual `AuthGate` component (not a unit test of the save function alone),
 * since the save effect deliberately lives in AuthGate itself (see its own
 * comment on why).
 */
describe("AuthGate — SPEC-EP-003 draft preservation on session expiry", () => {
  beforeEach(() => {
    localStorage.clear();
    useConversationStore.getState().reset();
    vi.clearAllMocks();
  });

  it("saves the in-progress draft the instant status reaches session_expired", async () => {
    // checkSession() fires unconditionally on every AuthGate mount — resolved
    // consistently with the pre-seeded "authenticated" state below (same
    // subject) so the two don't race each other before the test manually
    // drives session_expired.
    const token = fakeJwt({ sub: "employee-1" });
    vi.mocked(fetchBrowserSessionToken).mockResolvedValue({ accessToken: token, expiresInSeconds: 300 });
    useAuthStore.setState({ status: "authenticated", accessToken: token, lastKnownSubject: "employee-1", error: null });
    useConversationStore.setState({ conversationId: "conv-1", draftText: "my vpn keeps disconnecting" });

    renderWithProviders(<AuthGate />);
    await screen.findByRole("heading", { name: /opsmind support/i });

    useAuthStore.setState({ status: "session_expired", accessToken: null });

    expect(await screen.findByText(/your session has ended/i)).toBeInTheDocument();
    expect(loadDraft("employee-1", "conv-1")).toBe("my vpn keeps disconnecting");
  });

  it("never writes a draft when there was nothing typed", async () => {
    const token = fakeJwt({ sub: "employee-1" });
    vi.mocked(fetchBrowserSessionToken).mockResolvedValue({ accessToken: token, expiresInSeconds: 300 });
    useAuthStore.setState({ status: "authenticated", accessToken: token, lastKnownSubject: "employee-1", error: null });
    useConversationStore.setState({ conversationId: "conv-1", draftText: "" });

    renderWithProviders(<AuthGate />);
    await screen.findByRole("heading", { name: /opsmind support/i });

    useAuthStore.setState({ status: "session_expired", accessToken: null });
    await screen.findByText(/your session has ended/i);

    expect(loadDraft("employee-1", "conv-1")).toBeNull();
  });
});
