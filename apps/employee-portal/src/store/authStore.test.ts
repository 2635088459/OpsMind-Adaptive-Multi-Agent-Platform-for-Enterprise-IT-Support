import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";

// vi.mock's factory is hoisted above this file's own top-level statements, so the
// class it references must be created through vi.hoisted() rather than declared
// as a plain top-level const — otherwise it is read before its own initialization.
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
    vi.unstubAllGlobals();
  });

  it("transitions checking -> authenticated when a real token comes back", async () => {
    vi.mocked(fetchBrowserSessionToken).mockResolvedValue({ accessToken: "tok-1", expiresInSeconds: 300 });

    await useAuthStore.getState().checkSession();

    expect(useAuthStore.getState().status).toBe("authenticated");
    expect(useAuthStore.getState().accessToken).toBe("tok-1");
  });

  it("transitions checking -> unauthenticated on a real 401 (null token)", async () => {
    vi.mocked(fetchBrowserSessionToken).mockResolvedValue(null);

    await useAuthStore.getState().checkSession();

    expect(useAuthStore.getState().status).toBe("unauthenticated");
    expect(useAuthStore.getState().error).toBeNull();
  });

  it("surfaces a real outage as an honest error, still landing on unauthenticated", async () => {
    vi.mocked(fetchBrowserSessionToken).mockRejectedValue(new Error("network down"));

    await useAuthStore.getState().checkSession();

    expect(useAuthStore.getState().status).toBe("unauthenticated");
    expect(useAuthStore.getState().error).toBe("network down");
  });

  it("loginWithPassword() authenticates an employee (no support role) in place", async () => {
    vi.mocked(passwordLogin).mockResolvedValue({ accessToken: fakeJwt([]), expiresInSeconds: 300 });

    await useAuthStore.getState().loginWithPassword("test.agent", "test-password");

    expect(passwordLogin).toHaveBeenCalledExactlyOnceWith("opsmind", "test.agent", "test-password");
    expect(useAuthStore.getState().status).toBe("authenticated");
  });

  it("loginWithPassword() re-authenticates against support-console and hands off a support user", async () => {
    const assign = vi.fn();
    vi.stubGlobal("location", { assign, href: "http://localhost:5173/" });
    vi.mocked(passwordLogin)
      .mockResolvedValueOnce({ accessToken: fakeJwt(["support_agent"]), expiresInSeconds: 300 })
      .mockResolvedValueOnce({ accessToken: fakeJwt(["support_agent"]), expiresInSeconds: 300 });

    await useAuthStore.getState().loginWithPassword("support.agent", "test-password");

    expect(passwordLogin).toHaveBeenNthCalledWith(1, "opsmind", "support.agent", "test-password");
    expect(passwordLogin).toHaveBeenNthCalledWith(2, "support-console", "support.agent", "test-password");
    expect(assign).toHaveBeenCalledOnce();
  });

  it("loginWithPassword() surfaces invalid credentials as an honest error", async () => {
    vi.mocked(passwordLogin).mockRejectedValue(new FakeInvalidCredentialsError("Invalid username or password."));

    await useAuthStore.getState().loginWithPassword("test.agent", "wrong");

    expect(useAuthStore.getState().status).toBe("unauthenticated");
    expect(useAuthStore.getState().error).toBe("Invalid username or password.");
  });

  it("signOut() calls the real logout endpoint and resets to unauthenticated", async () => {
    useAuthStore.setState({
      status: "authenticated", accessToken: fakeJwt([]), error: null, roles: [], lastKnownSubject: "sub-1",
    });
    vi.mocked(logout).mockResolvedValue(undefined);

    await useAuthStore.getState().signOut();

    expect(logout).toHaveBeenCalledOnce();
    expect(useAuthStore.getState().status).toBe("unauthenticated");
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(useAuthStore.getState().lastKnownSubject).toBeNull();
  });

  it("signOut() still lands on unauthenticated even if the BFF call fails", async () => {
    useAuthStore.setState({ status: "authenticated", accessToken: fakeJwt([]), error: null, roles: [] });
    vi.mocked(logout).mockRejectedValue(new Error("network down"));

    await useAuthStore.getState().signOut();

    expect(useAuthStore.getState().status).toBe("unauthenticated");
  });
});

describe("useAuthStore — SPEC-EP-002 silent refresh", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "checking", accessToken: null, error: null, roles: [] });
    vi.clearAllMocks();
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it("schedules a real refresh attempt 60s before the token's own real expiry", async () => {
    vi.mocked(fetchBrowserSessionToken).mockResolvedValue({ accessToken: "tok-1", expiresInSeconds: 300 });
    await useAuthStore.getState().checkSession();

    vi.mocked(fetchBrowserSessionToken).mockResolvedValue({ accessToken: "tok-2", expiresInSeconds: 300 });
    await vi.advanceTimersByTimeAsync(239_000);
    expect(useAuthStore.getState().accessToken).toBe("tok-1");

    await vi.advanceTimersByTimeAsync(2_000);
    expect(useAuthStore.getState().accessToken).toBe("tok-2");
    expect(useAuthStore.getState().status).toBe("authenticated");
  });

  it("a successful refresh re-schedules the next one", async () => {
    vi.mocked(fetchBrowserSessionToken).mockResolvedValue({ accessToken: "tok-1", expiresInSeconds: 120 });
    await useAuthStore.getState().checkSession();

    vi.mocked(fetchBrowserSessionToken).mockResolvedValue({ accessToken: "tok-2", expiresInSeconds: 120 });
    await vi.advanceTimersByTimeAsync(60_000);
    expect(useAuthStore.getState().accessToken).toBe("tok-2");

    vi.mocked(fetchBrowserSessionToken).mockResolvedValue({ accessToken: "tok-3", expiresInSeconds: 120 });
    await vi.advanceTimersByTimeAsync(60_000);
    expect(useAuthStore.getState().accessToken).toBe("tok-3");
  });

  it("a real 401 on refresh (session genuinely revoked) transitions to session_expired", async () => {
    vi.mocked(fetchBrowserSessionToken).mockResolvedValue({ accessToken: "tok-1", expiresInSeconds: 60 });
    await useAuthStore.getState().checkSession();

    vi.mocked(fetchBrowserSessionToken).mockResolvedValue(null);
    await vi.advanceTimersByTimeAsync(60_000);

    expect(useAuthStore.getState().status).toBe("session_expired");
    expect(useAuthStore.getState().accessToken).toBeNull();
  });

  it("a 200 whose own expiresInSeconds already reached zero is treated as session_expired, not a valid refresh", async () => {
    vi.mocked(fetchBrowserSessionToken).mockResolvedValue({ accessToken: "tok-1", expiresInSeconds: 60 });
    await useAuthStore.getState().checkSession();

    vi.mocked(fetchBrowserSessionToken).mockResolvedValue({ accessToken: "tok-1", expiresInSeconds: 0 });
    await vi.advanceTimersByTimeAsync(60_000);

    expect(useAuthStore.getState().status).toBe("session_expired");
  });

  it("a real outage during refresh also lands on session_expired, not stuck token_refreshing", async () => {
    vi.mocked(fetchBrowserSessionToken).mockResolvedValue({ accessToken: "tok-1", expiresInSeconds: 60 });
    await useAuthStore.getState().checkSession();

    vi.mocked(fetchBrowserSessionToken).mockRejectedValue(new Error("network down"));
    await vi.advanceTimersByTimeAsync(60_000);

    expect(useAuthStore.getState().status).toBe("session_expired");
  });
});
