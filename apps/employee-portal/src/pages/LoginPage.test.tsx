import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, within } from "@testing-library/react";
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

describe("LoginPage (unified sign-in front door)", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "unauthenticated", accessToken: null, error: null, roles: [] });
    vi.clearAllMocks();
  });

  it("lists all three persona channels", () => {
    render(<LoginPage />);
    const channels = within(screen.getByRole("list", { name: /support channels/i }));
    expect(channels.getByText("Employee support")).toBeInTheDocument();
    expect(channels.getByText("IT staff")).toBeInTheDocument();
    expect(channels.getByText("IT supervisor")).toBeInTheDocument();
  });

  it("shows the employee and IT-staff demo accounts but not the supervisor's", () => {
    render(<LoginPage />);
    expect(screen.getByText(/test\.agent \/ test-password/)).toBeInTheDocument();
    expect(screen.getByText(/support\.agent \/ test-password/)).toBeInTheDocument();
    expect(screen.queryByText(/support\.admin/)).not.toBeInTheDocument();
  });

  it("submits the typed username/password to loginWithPassword — no navigation", async () => {
    render(<LoginPage />);
    await userEvent.type(screen.getByLabelText(/username/i), "test.agent");
    await userEvent.type(screen.getByLabelText(/password/i), "test-password");
    await userEvent.click(screen.getByRole("button", { name: /^sign in$/i }));

    expect(vi.mocked(passwordLogin)).toHaveBeenCalledExactlyOnceWith("opsmind", "test.agent", "test-password");
  });

  it("the 'Use' button on a demo account fills the form instead of submitting it", async () => {
    render(<LoginPage />);
    await userEvent.click(screen.getAllByRole("button", { name: /^use$/i })[0]);

    expect(screen.getByLabelText(/username/i)).toHaveValue("test.agent");
    expect(screen.getByLabelText(/password/i)).toHaveValue("test-password");
    expect(passwordLogin).not.toHaveBeenCalled();
  });

  it("disables the submit button until both fields are filled", () => {
    render(<LoginPage />);
    expect(screen.getByRole("button", { name: /^sign in$/i })).toBeDisabled();
  });
});
