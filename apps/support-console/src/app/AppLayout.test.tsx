import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { TICKET_WORKFLOW_BASE_URL } from "@/lib/env";

vi.mock("@/lib/authClient", () => ({
  fetchBrowserSessionToken: vi.fn(),
  beginLogin: vi.fn(),
}));

import { fetchBrowserSessionToken } from "@/lib/authClient";
import { useAuthStore } from "@/store/authStore";
import { AppLayout } from "@/app/AppLayout";
import { QueuePage } from "@/pages/QueuePage";

function fakeJwt(roles: string[]): string {
  const b64 = (obj: unknown) =>
    btoa(JSON.stringify(obj)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  return `${b64({ alg: "none" })}.${b64({ preferred_username: "agent-1", realm_access: { roles } })}.sig`;
}

function renderAt(path: string) {
  const router = createMemoryRouter(
    [{ path: "/", element: <AppLayout />, children: [{ index: true, element: <QueuePage /> }] }],
    { initialEntries: [path] },
  );
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

describe("AppLayout", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "checking", accessToken: null, error: null, roles: [] });
    vi.clearAllMocks();
    server.use(
      http.get(`${TICKET_WORKFLOW_BASE_URL}/api/v1/support/tickets`, () =>
        HttpResponse.json({
          items: [],
          page: { limit: 25, hasMore: false, nextCursor: null, evaluationTime: "2026-01-01T00:00:00Z", consistency: "LIVE" },
          sort: { version: 1, fields: [] },
          appliedFilters: {
            status: [], priority: [], applicationCode: [], assignedTeam: [], assignedAgent: null,
            unassignedOnly: false, slaState: [], createdFrom: null, createdTo: null,
          },
        }),
      ),
    );
  });

  it("shows the login page when the session check resolves unauthenticated", async () => {
    vi.mocked(fetchBrowserSessionToken).mockResolvedValue(null);
    renderAt("/");
    expect(await screen.findByRole("button", { name: /^sign in$/i })).toBeInTheDocument();
  });

  it("renders the queue route with nav once authenticated", async () => {
    vi.mocked(fetchBrowserSessionToken).mockResolvedValue({ accessToken: fakeJwt([]), expiresInSeconds: 300 });
    renderAt("/");
    expect(await screen.findByRole("link", { name: "Queue" })).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "Queue" })).toBeInTheDocument();
  });

  it("hides the Admin nav link for a non-admin", async () => {
    vi.mocked(fetchBrowserSessionToken).mockResolvedValue({ accessToken: fakeJwt(["support_agent"]), expiresInSeconds: 300 });
    renderAt("/");
    await screen.findByRole("link", { name: "Queue" });
    expect(screen.queryByRole("link", { name: "Admin" })).not.toBeInTheDocument();
  });

  it("shows the Admin nav link for a support_admin", async () => {
    vi.mocked(fetchBrowserSessionToken).mockResolvedValue({ accessToken: fakeJwt(["support_admin"]), expiresInSeconds: 300 });
    renderAt("/");
    expect(await screen.findByRole("link", { name: "Admin" })).toBeInTheDocument();
  });
});
