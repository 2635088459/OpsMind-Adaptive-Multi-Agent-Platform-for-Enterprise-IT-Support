import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";

vi.mock("@/lib/authClient", () => ({
  fetchBrowserSessionToken: vi.fn(),
  beginLogin: vi.fn(),
}));

import { fetchBrowserSessionToken, beginLogin } from "@/lib/authClient";
import { useAuthStore } from "@/store/authStore";

describe("useAuthStore", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "checking", accessToken: null, error: null });
    vi.clearAllMocks();
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

  it("login() sets login_in_progress and delegates the real navigation to authClient", () => {
    useAuthStore.getState().login();

    expect(useAuthStore.getState().status).toBe("login_in_progress");
    expect(beginLogin).toHaveBeenCalledOnce();
  });
});

describe("useAuthStore — SPEC-EP-002 silent refresh", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "checking", accessToken: null, error: null });
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
