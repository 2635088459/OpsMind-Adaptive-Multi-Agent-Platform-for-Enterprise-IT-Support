import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { useAuthStore } from "@/store/authStore";
import { MockEventSource } from "@/test/mockEventSource";
import { useTicketStatusStream } from "@/features/ticket/useTicketStatusStream";

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient();
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

describe("useTicketStatusStream — SPEC-EP-014/020", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "authenticated", accessToken: "fake-token", error: null });
    MockEventSource.reset();
    vi.stubGlobal("EventSource", MockEventSource);
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it("SPEC-EP-014: opens a connection and reaches 'connected' on open", () => {
    const { result } = renderHook(() => useTicketStatusStream("ticket-1"), { wrapper });

    expect(result.current).toBe("connecting");
    act(() => MockEventSource.latest().triggerOpen());

    expect(result.current).toBe("connected");
  });

  it("SPEC-EP-020: a drop triggers reconnecting, then connected once the new connection opens", async () => {
    const { result } = renderHook(() => useTicketStatusStream("ticket-1"), { wrapper });
    act(() => MockEventSource.latest().triggerOpen());
    expect(result.current).toBe("connected");

    act(() => MockEventSource.latest().triggerError());
    expect(result.current).toBe("reconnecting");
    expect(MockEventSource.instances[0].closed).toBe(true);

    await act(() => vi.advanceTimersByTimeAsync(1_000));
    expect(MockEventSource.instances).toHaveLength(2);

    act(() => MockEventSource.latest().triggerOpen());
    expect(result.current).toBe("connected");
  });

  it("SPEC-EP-020: backoff doubles on a second drop that follows the first without a successful reconnect in between", async () => {
    renderHook(() => useTicketStatusStream("ticket-1"), { wrapper });
    act(() => MockEventSource.latest().triggerOpen());

    // First drop: attempt 0 -> 1000ms backoff.
    act(() => MockEventSource.latest().triggerError());
    await act(() => vi.advanceTimersByTimeAsync(999));
    expect(MockEventSource.instances).toHaveLength(1);
    await act(() => vi.advanceTimersByTimeAsync(1));
    expect(MockEventSource.instances).toHaveLength(2);

    // Second drop, WITHOUT triggering open on instance #2 first (a real repeated-failure
    // scenario) — attempt is now 1, so backoff should double to 2000ms, not repeat 1000ms.
    act(() => MockEventSource.latest().triggerError());
    await act(() => vi.advanceTimersByTimeAsync(1_999));
    expect(MockEventSource.instances).toHaveLength(2);
    await act(() => vi.advanceTimersByTimeAsync(1));
    expect(MockEventSource.instances).toHaveLength(3);
  });

  it("SPEC-EP-020 §16: exhausting the backoff cap without success reaches 'failed', never pretending to still be live", async () => {
    const { result } = renderHook(() => useTicketStatusStream("ticket-1"), { wrapper });
    act(() => MockEventSource.latest().triggerOpen());

    // MAX_RECONNECT_ATTEMPTS (6) reconnects are scheduled before the 7th
    // error's own check finally finds attempt >= 6 and gives up.
    for (let i = 0; i < 6; i += 1) {
      act(() => MockEventSource.latest().triggerError());
      await act(() => vi.runOnlyPendingTimersAsync());
    }
    expect(result.current).toBe("reconnecting");

    act(() => MockEventSource.latest().triggerError());
    expect(result.current).toBe("failed");
  });

  it("BI-EP-004: re-fetches full state on reconnect rather than resuming onto stale data", async () => {
    const queryClient = new QueryClient();
    const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");
    renderHook(() => useTicketStatusStream("ticket-1"), {
      wrapper: ({ children }) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>,
    });
    act(() => MockEventSource.latest().triggerOpen());
    invalidateSpy.mockClear();

    act(() => MockEventSource.latest().triggerError());
    await act(() => vi.advanceTimersByTimeAsync(1_000));
    act(() => MockEventSource.latest().triggerOpen());

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["ticket", "ticket-1"] });
  });
});
