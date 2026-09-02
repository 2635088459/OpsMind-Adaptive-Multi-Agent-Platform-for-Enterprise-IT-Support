import { describe, it, expect, vi, afterEach } from "vitest";
import { beginLogin, fetchBrowserSessionToken } from "@/lib/authClient";

describe("fetchBrowserSessionToken", () => {
  const originalFetch = global.fetch;

  afterEach(() => {
    global.fetch = originalFetch;
    vi.restoreAllMocks();
  });

  it("returns the real access token on a 200 response", async () => {
    global.fetch = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ accessToken: "real-token", expiresInSeconds: 280 }), { status: 200 }),
    );

    const result = await fetchBrowserSessionToken();

    expect(result).toEqual({ accessToken: "real-token", expiresInSeconds: 280 });
    expect(global.fetch).toHaveBeenCalledWith(
      expect.stringContaining("/api/v1/session/browser-token"),
      expect.objectContaining({ credentials: "include" }),
    );
  });

  it("returns null on a 401 — the real 'no session yet' signal, not an error", async () => {
    global.fetch = vi.fn().mockResolvedValue(new Response(null, { status: 401 }));

    const result = await fetchBrowserSessionToken();

    expect(result).toBeNull();
  });

  it("throws on a 500 — a real outage is not the same as 'please log in'", async () => {
    global.fetch = vi.fn().mockResolvedValue(new Response(null, { status: 500 }));

    await expect(fetchBrowserSessionToken()).rejects.toThrow(/status 500/);
  });
});

describe("beginLogin", () => {
  it("performs a real top-level navigation to the BFF's own login-initiation endpoint, not a fetch", () => {
    const assign = vi.fn();
    vi.stubGlobal("location", { assign });

    beginLogin();

    expect(assign).toHaveBeenCalledWith(expect.stringContaining("/oauth2/authorization/opsmind"));
  });
});
