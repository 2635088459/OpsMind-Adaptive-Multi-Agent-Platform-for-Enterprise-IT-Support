import { useAttachmentStore } from "@/features/attachment/attachmentStore";
import { validateAttachment } from "@/features/attachment/validation";
import { uploadAttachment } from "@/features/attachment/api";

/**
 * SPEC-EP-010/011 together: select file → `VALIDATING` (synchronous,
 * SPEC-EP-011) → pass → `UPLOADING` → `READY`/`FAILED`; fail → `FAILED`
 * immediately, no upload attempted. A retried upload of the same file is a
 * fresh attempt (§12), not an idempotency-keyed replay — this hook is
 * simply called again with a new staged id.
 */
export function useUploadAttachment() {
  const stage = useAttachmentStore((state) => state.stage);
  const markUploading = useAttachmentStore((state) => state.markUploading);
  const markReady = useAttachmentStore((state) => state.markReady);
  const markFailed = useAttachmentStore((state) => state.markFailed);

  return async function upload(file: File): Promise<void> {
    const id = crypto.randomUUID();
    stage(id, file.name);

    const validation = validateAttachment(file);
    if (!validation.valid) {
      markFailed(id, validation.message ?? "This file could not be attached.");
      return;
    }

    markUploading(id);
    try {
      const { ref } = await uploadAttachment(file);
      markReady(id, ref);
    } catch (error) {
      markFailed(id, error instanceof Error ? error.message : "Upload failed.");
    }
  };
}
