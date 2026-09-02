import { describe, it, expect, vi, beforeEach } from "vitest";
import { useTurnStore } from "@/features/conversation/turnStore";

describe("useTurnStore", () => {
  beforeEach(() => {
    useTurnStore.setState({ state: "IDLE" });
  });

  it("applies a legal transition", () => {
    useTurnStore.getState().dispatch("sendMessage");
    expect(useTurnStore.getState().state).toBe("SENDING");
  });

  it("no-ops (does not throw, does not change state) on an illegal transition, logging a warning", () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});

    expect(() => useTurnStore.getState().dispatch("confirmClicked")).not.toThrow();

    expect(useTurnStore.getState().state).toBe("IDLE");
    expect(warn).toHaveBeenCalledOnce();
    warn.mockRestore();
  });

  it("seed sets the state directly, bypassing the transition table (SPEC-EP-015 resume)", () => {
    useTurnStore.getState().seed("AWAITING_CONFIRMATION");
    expect(useTurnStore.getState().state).toBe("AWAITING_CONFIRMATION");
  });
});
