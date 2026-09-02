import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";

vi.mock("@/lib/authClient", () => ({
  fetchBrowserSessionToken: vi.fn(),
  beginLogin: vi.fn(),
}));

import { fetchBrowserSessionToken } from "@/lib/authClient";
import { useAuthStore } from "@/store/authStore";
import { AuthGate } from "@/app/AuthGate";

describe("AuthGate", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "checking", accessToken: null, error: null });
    vi.clearAllMocks();
  });

  it("renders the login page once the session check resolves unauthenticated", async () => {
    vi.mocked(fetchBrowserSessionToken).mockResolvedValue(null);

    render(<AuthGate />);

    expect(await screen.findByRole("button", { name: /sign in with company account/i })).toBeInTheDocument();
  });

  it("renders the home page once the session check resolves authenticated", async () => {
    vi.mocked(fetchBrowserSessionToken).mockResolvedValue({ accessToken: "a.b.c", expiresInSeconds: 300 });

    render(<AuthGate />);

    expect(await screen.findByRole("heading", { name: /welcome/i })).toBeInTheDocument();
  });
});
