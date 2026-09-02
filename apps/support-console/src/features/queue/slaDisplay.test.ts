import { describe, it, expect } from "vitest";
import { computeSlaDisplay, formatRemaining } from "@/features/queue/slaDisplay";

const NOW = new Date("2026-01-01T12:00:00Z");

describe("computeSlaDisplay", () => {
  it("SPEC-SC-004: a ticket 10 minutes from breach shows an urgent countdown", () => {
    const result = computeSlaDisplay("ACTIVE", "2026-01-01T12:10:00Z", NOW);
    expect(result.state).toBe("urgent");
    expect(result.remainingMs).toBe(10 * 60 * 1000);
  });

  it("a ticket past its ACTIVE deadline shows overdue", () => {
    const result = computeSlaDisplay("ACTIVE", "2026-01-01T11:00:00Z", NOW);
    expect(result.state).toBe("overdue");
  });

  it("BREACHED is authoritative regardless of the raw due-date math", () => {
    const result = computeSlaDisplay("BREACHED", "2099-01-01T00:00:00Z", NOW);
    expect(result.state).toBe("overdue");
  });

  it("a comfortable ticket (>1h remaining) is not flagged urgent", () => {
    const result = computeSlaDisplay("ACTIVE", "2026-01-01T15:00:00Z", NOW);
    expect(result.state).toBe("comfortable");
  });

  it("§16: a missing/null SLA deadline omits the countdown gracefully, never a broken/negative one", () => {
    const result = computeSlaDisplay("ACTIVE", null, NOW);
    expect(result.state).toBe("missing");
    expect(result.remainingMs).toBeNull();
  });

  it.each(["MET", "CANCELLED", "PAUSED"])("%s is inactive, no countdown shown", (state) => {
    const result = computeSlaDisplay(state, "2026-01-01T13:00:00Z", NOW);
    expect(result.state).toBe("inactive");
  });
});

describe("formatRemaining", () => {
  it("formats sub-hour durations in minutes", () => {
    expect(formatRemaining(10 * 60 * 1000)).toBe("10m");
  });

  it("formats multi-hour durations as hours+minutes", () => {
    expect(formatRemaining(125 * 60 * 1000)).toBe("2h 5m");
  });
});
