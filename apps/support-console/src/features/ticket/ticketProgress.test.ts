import { describe, it, expect } from "vitest";
import { computeTicketProgress } from "@/features/ticket/ticketProgress";

describe("computeTicketProgress", () => {
  it("NEW, unassigned -> triage is current, everything else todo", () => {
    const p = computeTicketProgress("NEW", null);
    expect(p.current).toBe("triage");
    expect(p.closed).toBe(false);
    expect(p.steps.map((s) => s.state)).toEqual(["current", "todo", "todo", "todo"]);
  });

  /**
   * The real bug this whole pipeline was built to avoid: a team-routed,
   * not-yet-individually-assigned ticket (TRIAGED, agentId null) must
   * compute "assign" as current, never "triage" again (triage is already
   * done) and never skip straight to "start" (assign isn't done just
   * because a team owns it — see the `initiallyAssigned` fix this same
   * session).
   */
  it("TRIAGED, unassigned -> assign is current, triage reads done", () => {
    const p = computeTicketProgress("TRIAGED", null);
    expect(p.current).toBe("assign");
    expect(p.steps.map((s) => s.state)).toEqual(["done", "current", "todo", "todo"]);
  });

  it("ASSIGNED -> start is current, triage and assign read done", () => {
    const p = computeTicketProgress("ASSIGNED", "agent-1");
    expect(p.current).toBe("start");
    expect(p.steps.map((s) => s.state)).toEqual(["done", "done", "current", "todo"]);
  });

  it("IN_PROGRESS -> resolve is current", () => {
    const p = computeTicketProgress("IN_PROGRESS", "agent-1");
    expect(p.current).toBe("resolve");
    expect(p.steps.map((s) => s.state)).toEqual(["done", "done", "done", "current"]);
  });

  it("WAITING_FOR_APPROVAL -> resolve is current, same as IN_PROGRESS", () => {
    const p = computeTicketProgress("WAITING_FOR_APPROVAL", "agent-1");
    expect(p.current).toBe("resolve");
  });

  it("a terminal status closes every step and leaves nothing current", () => {
    for (const status of ["RESOLVED", "CLOSED", "CANCELLED", "FAILED_FINAL"]) {
      const p = computeTicketProgress(status, "agent-1");
      expect(p.closed).toBe(true);
      expect(p.current).toBeNull();
      expect(p.steps.every((s) => s.state === "done")).toBe(true);
    }
  });
});
