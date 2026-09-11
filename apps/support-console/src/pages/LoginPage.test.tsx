import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

vi.mock("@/lib/authClient", () => ({
  fetchBrowserSessionToken: vi.fn(),
  passwordLogin: vi.fn(),
  InvalidCredentialsError: class extends Error {},
  logout: vi.fn(),
}));

import { passwordLogin } from "@/lib/authClient";
import { useAuthStore } from "@/store/authStore";
import { LoginPage } from "@/pages/LoginPage";

describe("LoginPage", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "unauthenticated", accessToken: null, error: null, roles: [] });
    vi.clearAllMocks();
  });

  it("submits the typed username/password to loginWithPassword — no navigation", async () => {
    render(<LoginPage />);
    await userEvent.type(screen.getByLabelText(/username/i), "support.agent");
    await userEvent.type(screen.getByLabelText(/password/i), "test-password");
    await userEvent.click(screen.getByRole("button", { name: /^sign in$/i }));

    expect(vi.mocked(passwordLogin)).toHaveBeenCalledExactlyOnceWith("support.agent", "test-password");
  });

  it("disables the submit button until both fields are filled", () => {
    render(<LoginPage />);
    expect(screen.getByRole("button", { name: /^sign in$/i })).toBeDisabled();
  });

  it("shows the store's error message", () => {
    useAuthStore.setState({ error: "Invalid username or password." });
    render(<LoginPage />);
    expect(screen.getByRole("alert")).toHaveTextContent("Invalid username or password.");
  });
});
