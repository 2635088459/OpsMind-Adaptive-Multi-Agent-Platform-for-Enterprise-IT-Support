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
  });

  it("returns null on a 401", async () => {
    global.fetch = vi.fn().mockResolvedValue(new Response(null, { status: 401 }));

    expect(await fetchBrowserSessionToken()).toBeNull();
  });
});

describe("passwordLogin", () => {
  const originalFetch = global.fetch;

  afterEach(() => {
    global.fetch = originalFetch;
    vi.restoreAllMocks();
  });

  it("POSTs the support-console registration id + credentials and returns the real token on success", async () => {
    global.fetch = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ accessToken: "real-token", expiresInSeconds: 900 }), { status: 200 }),
    );

    const result = await passwordLogin("support.agent", "test-password");

    expect(result).toEqual({ accessToken: "real-token", expiresInSeconds: 900 });
    expect(global.fetch).toHaveBeenCalledWith(
      expect.stringContaining("/api/v1/session/password-login"),
      expect.objectContaining({
        method: "POST",
        credentials: "include",
        body: JSON.stringify({ registrationId: "support-console", username: "support.agent", password: "test-password" }),
      }),
    );
  });

  it("throws InvalidCredentialsError on a 401", async () => {
    global.fetch = vi.fn().mockResolvedValue(new Response(null, { status: 401 }));

    await expect(passwordLogin("support.agent", "wrong")).rejects.toThrow(InvalidCredentialsError);
  });
});
