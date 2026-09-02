import { describe, it, expect, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { useAuthStore } from "@/store/authStore";
import { AdminOnly } from "@/features/auth/AdminOnly";

describe("AdminOnly — SPEC-SC-002", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "authenticated", accessToken: "x", error: null, roles: [] });
  });

  it("renders its children for an admin-role session", () => {
    useAuthStore.setState({ roles: ["support_agent", "support_admin"] });

    render(<AdminOnly><button>Reconcile</button></AdminOnly>);

    expect(screen.getByRole("button", { name: /reconcile/i })).toBeInTheDocument();
  });

  it("renders nothing (not disabled, not visible-but-blocked) for an agent-only session", () => {
    useAuthStore.setState({ roles: ["support_agent"] });

    render(<AdminOnly><button>Reconcile</button></AdminOnly>);

    expect(screen.queryByRole("button", { name: /reconcile/i })).not.toBeInTheDocument();
  });

  it("§16: defaults to the most restrictive rendering when roles are missing entirely", () => {
    useAuthStore.setState({ roles: [] });

    render(<AdminOnly><button>Reconcile</button></AdminOnly>);

    expect(screen.queryByRole("button", { name: /reconcile/i })).not.toBeInTheDocument();
  });
});
