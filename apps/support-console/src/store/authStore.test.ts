import { describe, it, expect, vi, beforeEach } from "vitest";

const { FakeInvalidCredentialsError } = vi.hoisted(() => ({
  FakeInvalidCredentialsError: class extends Error {},
}));

vi.mock("@/lib/authClient", () => ({
  fetchBrowserSessionToken: vi.fn(),
  passwordLogin: vi.fn(),
  logout: vi.fn(),
  InvalidCredentialsError: FakeInvalidCredentialsError,
}));

import { fetchBrowserSessionToken, logout, passwordLogin } from "@/lib/authClient";
import { useAuthStore } from "@/store/authStore";

function fakeJwt(roles: string[]): string {
  const b64 = (o: unknown) => btoa(JSON.stringify(o)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  return `${b64({ alg: "none" })}.${b64({ sub: "u-1", realm_access: { roles } })}.sig`;
}

describe("useAuthStore", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "checking", accessToken: null, error: null, roles: [] });
    vi.clearAllMocks();
  });

  it("transitions checking -> authenticated when a real token comes back", async () => {
    vi.mocked(fetchBrowserSessionToken).mockResolvedValue({ accessToken: fakeJwt([]), expiresInSeconds: 300 });

    await useAuthStore.getState().checkSession();

    expect(useAuthStore.getState().status).toBe("authenticated");
  });

  it("loginWithPassword() authenticates against this app's own support-console registration", async () => {
    vi.mocked(passwordLogin).mockResolvedValue({ accessToken: fakeJwt(["support_agent"]), expiresInSeconds: 300 });

    await useAuthStore.getState().loginWithPassword("support.agent", "test-password");

    expect(passwordLogin).toHaveBeenCalledExactlyOnceWith("support.agent", "test-password");
    expect(useAuthStore.getState().status).toBe("authenticated");
    expect(useAuthStore.getState().roles).toEqual(["support_agent"]);
  });

  it("loginWithPassword() surfaces invalid credentials as an honest error", async () => {
    vi.mocked(passwordLogin).mockRejectedValue(new FakeInvalidCredentialsError("Invalid username or password."));

    await useAuthStore.getState().loginWithPassword("support.agent", "wrong");

    expect(useAuthStore.getState().status).toBe("unauthenticated");
    expect(useAuthStore.getState().error).toBe("Invalid username or password.");
  });

  it("signOut() calls the real logout endpoint and resets to unauthenticated", async () => {
    useAuthStore.setState({ status: "authenticated", accessToken: fakeJwt(["support_agent"]), error: null, roles: ["support_agent"] });
    vi.mocked(logout).mockResolvedValue(undefined);

    await useAuthStore.getState().signOut();

    expect(logout).toHaveBeenCalledOnce();
    expect(useAuthStore.getState().status).toBe("unauthenticated");
    expect(useAuthStore.getState().roles).toEqual([]);
  });
});
