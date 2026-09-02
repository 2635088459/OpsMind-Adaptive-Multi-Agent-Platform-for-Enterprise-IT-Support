import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { renderWithProviders } from "@/test/renderWithProviders";
import { AGENT_RUNTIME_BASE_URL } from "@/lib/env";

vi.mock("@/lib/authClient", () => ({
  fetchBrowserSessionToken: vi.fn(),
  beginLogin: vi.fn(),
}));

import { fetchBrowserSessionToken } from "@/lib/authClient";
import { useAuthStore } from "@/store/authStore";
import { useConversationStore } from "@/features/conversation/conversationStore";
import { AuthGate } from "@/app/AuthGate";

describe("AuthGate", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "checking", accessToken: null, error: null });
    useConversationStore.getState().reset();
    vi.clearAllMocks();
    // SPEC-EP-015: ConversationView always tries to resume the employee's most
    // recent conversation on mount — a real 404 (nothing to resume yet) is the
    // honest default for a test that isn't itself exercising that flow.
    server.use(http.get(`${AGENT_RUNTIME_BASE_URL}/api/v1/conversations/most-recent`, () => HttpResponse.json(
      { error: { code: "CONVERSATION_NOT_FOUND", message: "not found" } }, { status: 404 },
    )));
  });

  it("renders the login page once the session check resolves unauthenticated", async () => {
    vi.mocked(fetchBrowserSessionToken).mockResolvedValue(null);

    renderWithProviders(<AuthGate />);

    expect(await screen.findByRole("button", { name: /sign in with company account/i })).toBeInTheDocument();
  });

  it("renders the real conversation view once the session check resolves authenticated", async () => {
    vi.mocked(fetchBrowserSessionToken).mockResolvedValue({ accessToken: "a.b.c", expiresInSeconds: 300 });

    renderWithProviders(<AuthGate />);

    expect(await screen.findByRole("heading", { name: /opsmind support/i })).toBeInTheDocument();
  });
});
