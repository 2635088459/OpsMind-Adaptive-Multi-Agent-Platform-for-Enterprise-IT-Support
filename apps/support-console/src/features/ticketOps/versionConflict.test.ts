import { describe, it, expect } from "vitest";
import { ApiError } from "@/lib/apiError";
import { currentVersionFrom, isVersionConflict } from "@/features/ticketOps/versionConflict";

describe("versionConflict — SPEC-SC-013", () => {
  it("recognizes the real 412 VERSION_CONFLICT shape", () => {
    const error = new ApiError(412, { code: "VERSION_CONFLICT", message: "the ticket was changed by another operation", details: { currentVersion: 7 } });
    expect(isVersionConflict(error)).toBe(true);
    expect(currentVersionFrom(error)).toBe(7);
  });

  it("does not misclassify an unrelated 409 conflict as a version conflict", () => {
    const error = new ApiError(409, { code: "TICKET_ALREADY_ASSIGNED", message: "already assigned" });
    expect(isVersionConflict(error)).toBe(false);
  });

  it("does not misclassify a non-ApiError", () => {
    expect(isVersionConflict(new Error("boom"))).toBe(false);
  });
});
