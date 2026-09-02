import { describe, it, expect, beforeEach } from "vitest";
import { renderHook } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { useAuthStore } from "@/store/authStore";
import { useAttachmentStore } from "@/features/attachment/attachmentStore";
import { ATTACHMENTS_BASE_URL } from "@/lib/env";
import { useUploadAttachment } from "@/features/attachment/useUploadAttachment";

function makeFile(name: string, type: string, sizeBytes: number): File {
  return new File([new Uint8Array(sizeBytes)], name, { type });
}

describe("useUploadAttachment — SPEC-EP-010/011 full state machine", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "authenticated", accessToken: "fake-token", error: null });
    useAttachmentStore.getState().reset();
  });

  it("VALIDATING -> UPLOADING -> READY for a valid file against the MSW mock", async () => {
    server.use(http.post(`${ATTACHMENTS_BASE_URL}/api/v1/attachments`, () => HttpResponse.json({ ref: "attachment-ref-1" })));
    const { result } = renderHook(() => useUploadAttachment());
    const upload = result.current;

    await upload(makeFile("screenshot.png", "image/png", 1024));

    const [attachment] = useAttachmentStore.getState().attachments;
    expect(attachment.status).toBe("ready");
    expect(attachment.ref).toBe("attachment-ref-1");
  });

  it("VALIDATING -> FAILED for a rejected file, never attempting the upload call", async () => {
    let uploadCalled = false;
    server.use(http.post(`${ATTACHMENTS_BASE_URL}/api/v1/attachments`, () => {
      uploadCalled = true;
      return HttpResponse.json({ ref: "should-not-happen" });
    }));
    const { result } = renderHook(() => useUploadAttachment());
    const upload = result.current;

    await upload(makeFile("virus.exe", "application/x-msdownload", 100));

    const [attachment] = useAttachmentStore.getState().attachments;
    expect(attachment.status).toBe("failed");
    expect(attachment.errorMessage).toMatch(/unsupported file type/i);
    expect(uploadCalled).toBe(false);
  });

  it("UPLOADING -> FAILED on a real upload failure", async () => {
    server.use(http.post(`${ATTACHMENTS_BASE_URL}/api/v1/attachments`, () => HttpResponse.json(
      { error: { code: "INTERNAL_ERROR", message: "storage unavailable" } }, { status: 500 },
    )));
    const { result } = renderHook(() => useUploadAttachment());
    const upload = result.current;

    await upload(makeFile("screenshot.png", "image/png", 1024));

    const [attachment] = useAttachmentStore.getState().attachments;
    expect(attachment.status).toBe("failed");
  });
});
