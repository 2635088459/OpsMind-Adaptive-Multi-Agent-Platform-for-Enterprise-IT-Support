import { describe, it, expect, vi, beforeEach } from "vitest";
import { clearDraft, loadDraft, saveDraft } from "@/features/session/draftPreservation";

describe("draftPreservation", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("saves and loads a draft round trip", () => {
    saveDraft("employee-1", "conv-1", "my vpn is down");
    expect(loadDraft("employee-1", "conv-1")).toBe("my vpn is down");
  });

  it("BI-EP-006 §4: isolates drafts per subject, even for the same conversationId on a shared device", () => {
    saveDraft("employee-1", "conv-1", "employee one's draft");
    saveDraft("employee-2", "conv-1", "employee two's draft");

    expect(loadDraft("employee-1", "conv-1")).toBe("employee one's draft");
    expect(loadDraft("employee-2", "conv-1")).toBe("employee two's draft");
  });

  it("clearDraft removes exactly the one key", () => {
    saveDraft("employee-1", "conv-1", "text");
    clearDraft("employee-1", "conv-1");
    expect(loadDraft("employee-1", "conv-1")).toBeNull();
  });

  it("never writes an empty draft", () => {
    saveDraft("employee-1", "conv-1", "");
    expect(loadDraft("employee-1", "conv-1")).toBeNull();
  });

  it("§16: a write failure (quota exceeded) degrades silently, never throws", () => {
    const setItem = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("QuotaExceededError");
    });

    expect(() => saveDraft("employee-1", "conv-1", "text")).not.toThrow();

    setItem.mockRestore();
  });
});
