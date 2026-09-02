import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { renderHook, waitFor, act } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { AGENT_RUNTIME_BASE_URL } from "@/lib/env";
import { useOnlineStatus } from "@/features/session/useOnlineStatus";

describe("useOnlineStatus", () => {
  beforeEach(() => {
    server.use(http.get(`${AGENT_RUNTIME_BASE_URL}/health`, () => HttpResponse.json({ status: "UP" })));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("starts online when navigator.onLine is true", () => {
    const { result } = renderHook(() => useOnlineStatus());
    expect(result.current).toBe(true);
  });

  it("SPEC-EP-019: the browser's own offline event flips state immediately", async () => {
    const { result } = renderHook(() => useOnlineStatus());

    act(() => window.dispatchEvent(new Event("offline")));

    await waitFor(() => expect(result.current).toBe(false));
  });

  it("recovers only once the online event AND a real heartbeat both succeed", async () => {
    const { result } = renderHook(() => useOnlineStatus());
    act(() => window.dispatchEvent(new Event("offline")));
    await waitFor(() => expect(result.current).toBe(false));

    act(() => window.dispatchEvent(new Event("online")));

    await waitFor(() => expect(result.current).toBe(true));
  });

  it("a real heartbeat failure (backend unreachable) is also treated as offline", async () => {
    server.use(http.get(`${AGENT_RUNTIME_BASE_URL}/health`, () => HttpResponse.error()));
    const { result } = renderHook(() => useOnlineStatus());

    act(() => window.dispatchEvent(new Event("online")));

    await waitFor(() => expect(result.current).toBe(false));
  });
});
