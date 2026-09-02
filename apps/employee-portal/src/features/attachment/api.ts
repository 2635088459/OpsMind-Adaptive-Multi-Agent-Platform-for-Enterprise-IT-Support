/**
 * SPEC-EP-010 §13: `POST /api/v1/attachments` — the shared attachments
 * capability, chartered here but built as its own real service
 * (attachment-service, AttachmentController) since this domain doesn't own
 * it. MSW-mocked for this domain's own tests per that spec's own Definition
 * of Done; ATTACHMENTS_BASE_URL now points at the real running service, and
 * the real response shape ({ref}) matches this contract exactly, so no
 * other change was needed here.
 */
import { authedFetch } from "@/lib/httpClient";
import { ATTACHMENTS_BASE_URL } from "@/lib/env";

export async function uploadAttachment(file: File): Promise<{ ref: string }> {
  const formData = new FormData();
  formData.append("file", file);
  const response = await authedFetch(`${ATTACHMENTS_BASE_URL}/api/v1/attachments`, {
    method: "POST",
    body: formData,
  });
  return (await response.json()) as { ref: string };
}
