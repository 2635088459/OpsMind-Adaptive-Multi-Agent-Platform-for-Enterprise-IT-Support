/**
 * SPEC-EP-010 §13: `POST /api/v1/attachments` — the new independent shared
 * attachments capability, chartered but not yet designed anywhere in this
 * platform (confirmed: no such endpoint exists in any backend service's
 * own router). MSW-mocked for this domain's own tests per that spec's own
 * Definition of Done; this app has no real base URL to call yet, so a
 * production call here would genuinely fail until that capability exists —
 * an honest, explicitly-flagged gap, not a silently-fabricated success.
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
