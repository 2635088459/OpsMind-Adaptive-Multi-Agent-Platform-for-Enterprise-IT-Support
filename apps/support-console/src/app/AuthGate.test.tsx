import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { renderWithProviders } from "@/test/renderWithProviders";
import { TICKET_WORKFLOW_BASE_URL } from "@/lib/env";

vi.mock("@/lib/authClient", () => ({
  fetchBrowserSessionToken: vi.fn(),
  beginLogin: vi.fn(),
}));

import { fetchBrowserSessionToken } from "@/lib/authClient";
import { useAuthStore } from "@/store/authStore";
import { AuthGate } from "@/app/AuthGate";

describe("AuthGate", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "checking", accessToken: null, error: null, roles: [] });
    vi.clearAllMocks();
    // SPEC-SC-003: QueuePage always fetches the real queue on mount — a real
    // empty response is the honest default for a test not itself exercising that flow.
    server.use(http.get(`${TICKET_WORKFLOW_BASE_URL}/api/v1/support/tickets`, () => HttpResponse.json({
      items: [], page: { limit: 25, hasMore: false, nextCursor: null, evaluationTime: "2026-01-01T00:00:00Z", consistency: "LIVE" },
      sort: { version: 1, fields: [] },
      appliedFilters: { status: [], priority: [], applicationCode: [], assignedTeam: [], assignedAgent: null, unassignedOnly: false, slaState: [], createdFrom: null, createdTo: null },
    })));
  });

  it("renders the login page once the session check resolves unauthenticated", async () => {
    vi.mocked(fetchBrowserSessionToken).mockResolvedValue(null);

    renderWithProviders(<AuthGate />);

    expect(await screen.findByRole("button", { name: /^sign in$/i })).toBeInTheDocument();
  });

  it("renders the real queue view once the session check resolves authenticated", async () => {
    vi.mocked(fetchBrowserSessionToken).mockResolvedValue({ accessToken: "a.b.c", expiresInSeconds: 300 });

    renderWithProviders(<AuthGate />);

    expect(await screen.findByRole("heading", { name: /opsmind support console/i })).toBeInTheDocument();
  });
});
