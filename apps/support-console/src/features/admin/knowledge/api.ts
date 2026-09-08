import { authedFetch } from "@/lib/httpClient";
import { MEMORY_KNOWLEDGE_BASE_URL } from "@/lib/env";

const BASE = `${MEMORY_KNOWLEDGE_BASE_URL}/internal/memory/v1/admin`;

/** Mirrors memory-knowledge-service's `IngestDocumentRequest` (snake_case — that's a Python/FastAPI service). */
export interface IngestDocumentInput {
  sourceSystem: string;
  externalId: string;
  title: string;
  documentType: string;
  rawContent: string;
  ingestedBy: string;
  classification: string;
  acl: string[];
  extractGraph: boolean;
}

/** Mirrors `KnowledgeDocumentResponse`. */
export interface IngestedDocument {
  documentId: string;
  version: number;
  ingestionStatus: string;
  title: string;
  chunkCount: number;
  createdAt: string;
}

export async function ingestDocument(input: IngestDocumentInput): Promise<IngestedDocument> {
  const response = await authedFetch(`${BASE}/documents`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      source_system: input.sourceSystem,
      external_id: input.externalId,
      title: input.title,
      document_type: input.documentType,
      raw_content: input.rawContent,
      ingested_by: input.ingestedBy,
      classification: input.classification,
      acl: input.acl,
      extract_graph: input.extractGraph,
    }),
  });
  const body = (await response.json()) as {
    document_id: string;
    version: number;
    ingestion_status: string;
    title: string;
    chunk_count: number;
    created_at: string;
  };
  return {
    documentId: body.document_id,
    version: body.version,
    ingestionStatus: body.ingestion_status,
    title: body.title,
    chunkCount: body.chunk_count,
    createdAt: body.created_at,
  };
}
