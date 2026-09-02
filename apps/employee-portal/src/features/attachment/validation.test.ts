import { describe, it, expect } from "vitest";
import { MAX_FILE_SIZE_BYTES, validateAttachment } from "@/features/attachment/validation";

function makeFile(name: string, type: string, sizeBytes: number): File {
  return new File([new Uint8Array(sizeBytes)], name, { type });
}

describe("validateAttachment", () => {
  it("rejects an unsupported file type with a specific message", () => {
    const result = validateAttachment(makeFile("virus.exe", "application/x-msdownload", 100));

    expect(result).toEqual({ valid: false, reason: "unsupported-type", message: expect.stringMatching(/unsupported file type/i) });
  });

  it("rejects an oversized file with a distinct message from the wrong-type case", () => {
    const result = validateAttachment(makeFile("huge.png", "image/png", MAX_FILE_SIZE_BYTES + 1));

    expect(result.valid).toBe(false);
    expect(result.reason).toBe("too-large");
    expect(result.message).toMatch(/too large/i);
  });

  it("passes a valid file under the limit", () => {
    const result = validateAttachment(makeFile("screenshot.png", "image/png", 1024));

    expect(result).toEqual({ valid: true });
  });
});
