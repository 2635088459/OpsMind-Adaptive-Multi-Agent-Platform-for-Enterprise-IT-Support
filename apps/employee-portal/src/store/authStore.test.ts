import { describe, it, expect, vi, beforeEach } from "vitest";

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
