import { describe, it, expect, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { useAuthStore } from "@/store/authStore";
import { MEMORY_KNOWLEDGE_BASE_URL } from "@/lib/env";
import { ingestDocument } from "@/features/admin/knowledge/api";

const URL = `${MEMORY_KNOWLEDGE_BASE_URL}/internal/memory/v1/admin/documents`;

describe("knowledge admin api", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "authenticated", accessToken: "fake-token", error: null });
  });

  it("maps the camelCase form to the service's snake_case IngestDocumentRequest", async () => {
    let body: Record<string, unknown> | null = null;
    server.use(
      http.post(URL, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({
          document_id: "doc-1", version: 1, ingestion_status: "RECEIVED", title: "VPN runbook", chunk_count: 0,
          created_at: "2026-09-07T10:00:00Z",
        });
      }),
    );

    const result = await ingestDocument({
      sourceSystem: "manual", externalId: "vpn-runbook", title: "VPN runbook", documentType: "runbook",
      rawContent: "# VPN\nsteps", ingestedBy: "agent-1", classification: "INTERNAL", acl: ["EMPLOYEE"], extractGraph: true,
    });

    expect(body).toEqual({
      source_system: "manual", external_id: "vpn-runbook", title: "VPN runbook", document_type: "runbook",
      raw_content: "# VPN\nsteps", ingested_by: "agent-1", classification: "INTERNAL", acl: ["EMPLOYEE"], extract_graph: true,
    });
    expect(result).toEqual({
      documentId: "doc-1", version: 1, ingestionStatus: "RECEIVED", title: "VPN runbook", chunkCount: 0,
      createdAt: "2026-09-07T10:00:00Z",
    });
  });

  it("surfaces the shared error envelope as a throwing ApiError", async () => {
    server.use(http.post(URL, () => HttpResponse.json({ error: { code: "DOCUMENT_ALREADY_INGESTED", message: "exists" } }, { status: 409 })));

    await expect(
      ingestDocument({
        sourceSystem: "manual", externalId: "x", title: "x", documentType: "x", rawContent: "x",
        ingestedBy: "a", classification: "INTERNAL", acl: [], extractGraph: false,
      }),
    ).rejects.toMatchObject({ status: 409, code: "DOCUMENT_ALREADY_INGESTED" });
  });
});
