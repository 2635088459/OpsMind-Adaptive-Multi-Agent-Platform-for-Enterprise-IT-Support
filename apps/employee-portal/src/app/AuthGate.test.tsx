import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { renderWithProviders } from "@/test/renderWithProviders";
import { AGENT_RUNTIME_BASE_URL } from "@/lib/env";

vi.mock("@/lib/authClient", () => ({
  fetchBrowserSessionToken: vi.fn(),
  passwordLogin: vi.fn(),
  InvalidCredentialsError: class extends Error {},
  logout: vi.fn(),
}));

import { fetchBrowserSessionToken } from "@/lib/authClient";
import { useAuthStore } from "@/store/authStore";
import { useConversationStore } from "@/features/conversation/conversationStore";
import { AuthGate } from "@/app/AuthGate";

function fakeJwt(roles: string[]): string {
  const b64 = (o: unknown) =>
    btoa(JSON.stringify(o)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  return `${b64({ alg: "none" })}.${b64({ sub: "u-1", preferred_username: "someone", realm_access: { roles } })}.sig`;
}

describe("AuthGate", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "checking", accessToken: null, error: null, roles: [] });
    useConversationStore.getState().reset();
    vi.clearAllMocks();
    vi.unstubAllGlobals();
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

    expect(await screen.findByRole("button", { name: /^sign in$/i })).toBeInTheDocument();
  });

  it("renders the real conversation view once the session check resolves authenticated", async () => {
    vi.mocked(fetchBrowserSessionToken).mockResolvedValue({ accessToken: fakeJwt([]), expiresInSeconds: 300 });

    renderWithProviders(<AuthGate />);

    expect(await screen.findByRole("heading", { name: /opsmind support/i })).toBeInTheDocument();
  });

  it("hands a support-role user off to the Support Console instead of showing the portal", async () => {
    const assign = vi.fn();
    vi.stubGlobal("location", { href: "http://localhost:5173/", pathname: "/", search: "", hash: "", assign });
    vi.mocked(fetchBrowserSessionToken).mockResolvedValue({
      accessToken: fakeJwt(["support_agent"]), expiresInSeconds: 300,
    });

    renderWithProviders(<AuthGate />);

    expect(await screen.findByText(/taking you to the support console/i)).toBeInTheDocument();
    expect(assign).toHaveBeenCalledWith("http://localhost:5174");
    expect(screen.queryByRole("heading", { name: /opsmind support/i })).not.toBeInTheDocument();
  });

  it("keeps an employee (no support role) in the portal", async () => {
    const assign = vi.fn();
    vi.stubGlobal("location", { href: "http://localhost:5173/", pathname: "/", search: "", hash: "", assign });
    vi.mocked(fetchBrowserSessionToken).mockResolvedValue({
      accessToken: fakeJwt([]), expiresInSeconds: 300,
    });

    renderWithProviders(<AuthGate />);

    expect(await screen.findByRole("heading", { name: /opsmind support/i })).toBeInTheDocument();
    expect(assign).not.toHaveBeenCalled();
  });
});
