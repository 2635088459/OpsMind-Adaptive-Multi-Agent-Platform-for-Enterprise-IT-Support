import { describe, it, expect, vi, afterEach } from "vitest";
import { InvalidCredentialsError, fetchBrowserSessionToken, passwordLogin } from "@/lib/authClient";

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

describe("passwordLogin", () => {
  const originalFetch = global.fetch;

  afterEach(() => {
    global.fetch = originalFetch;
    vi.restoreAllMocks();
  });

  it("POSTs the registration id + credentials and returns the real token on success", async () => {
    global.fetch = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ accessToken: "real-token", expiresInSeconds: 900 }), { status: 200 }),
    );

    const result = await passwordLogin("opsmind", "test.agent", "test-password");

    expect(result).toEqual({ accessToken: "real-token", expiresInSeconds: 900 });
    expect(global.fetch).toHaveBeenCalledWith(
      expect.stringContaining("/api/v1/session/password-login"),
      expect.objectContaining({
        method: "POST",
        credentials: "include",
        body: JSON.stringify({ registrationId: "opsmind", username: "test.agent", password: "test-password" }),
      }),
    );
  });

  it("throws InvalidCredentialsError on a 401 — never says which of username/password was wrong", async () => {
    global.fetch = vi.fn().mockResolvedValue(new Response(null, { status: 401 }));

    await expect(passwordLogin("opsmind", "test.agent", "wrong")).rejects.toThrow(InvalidCredentialsError);
  });

  it("throws a real error on a 500 — a system fault, not 'please log in'", async () => {
    global.fetch = vi.fn().mockResolvedValue(new Response(null, { status: 500 }));

    await expect(passwordLogin("opsmind", "test.agent", "test-password")).rejects.toThrow(/status 500/);
  });
});
