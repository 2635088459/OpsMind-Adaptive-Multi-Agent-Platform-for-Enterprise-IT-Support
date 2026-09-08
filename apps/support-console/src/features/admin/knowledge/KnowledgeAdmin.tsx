import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { useAuthStore } from "@/store/authStore";
import { decodeJwtPayload } from "@/lib/jwt";
import { ingestDocument, type IngestDocumentInput } from "@/features/admin/knowledge/api";

const CLASSIFICATIONS = ["INTERNAL", "PUBLIC", "CONFIDENTIAL"] as const;

/**
 * Ingest a knowledge document into memory-knowledge-service so it becomes
 * retrievable by the agent's RAG path. A document goes RECEIVED → … → ACTIVE
 * asynchronously; the response shows the initial status + chunk count.
 */
export function KnowledgeAdmin() {
  const accessToken = useAuthStore((state) => state.accessToken);
  const actor =
    (accessToken && (decodeJwtPayload(accessToken)?.preferred_username as string | undefined)) || "support-console";

  const [form, setForm] = useState<IngestDocumentInput>({
    sourceSystem: "manual",
    externalId: "",
    title: "",
    documentType: "runbook",
    rawContent: "",
    ingestedBy: actor,
    classification: "INTERNAL",
    acl: [],
    extractGraph: false,
  });
  const [aclText, setAclText] = useState("");

  const mutation = useMutation({
    mutationFn: (input: IngestDocumentInput) => ingestDocument(input),
  });

  const set = <K extends keyof IngestDocumentInput>(key: K, value: IngestDocumentInput[K]) =>
    setForm((prev) => ({ ...prev, [key]: value }));

  const canSubmit =
    form.externalId.trim() && form.title.trim() && form.documentType.trim() && form.rawContent.trim() && !mutation.isPending;

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    if (!canSubmit) return;
    const acl = aclText.split(",").map((r) => r.trim()).filter(Boolean);
    mutation.mutate({ ...form, ingestedBy: actor, acl });
  };

  const input = "mt-1 w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-ink";
  const label = "block text-sm font-medium text-ink";

  return (
    <form onSubmit={submit} className="max-w-2xl" noValidate>
      {mutation.isSuccess ? (
        <p className="mb-4 rounded-md border border-border bg-surface-muted px-3 py-2 text-sm text-ink" data-testid="ingest-success">
          Ingested <span className="font-mono">{mutation.data.title}</span> — status {mutation.data.ingestionStatus},{" "}
          {mutation.data.chunkCount} chunk(s). Document id <span className="font-mono">{mutation.data.documentId}</span>.
        </p>
      ) : null}
      {mutation.isError ? (
        <p className="mb-4 rounded-md border border-danger/30 bg-danger/5 px-3 py-2 text-sm text-danger" data-testid="ingest-error">
          {(mutation.error as Error).message}
        </p>
      ) : null}

      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className={label} htmlFor="k-source">Source system</label>
          <input id="k-source" className={input} value={form.sourceSystem} onChange={(e) => set("sourceSystem", e.target.value)} />
        </div>
        <div>
          <label className={label} htmlFor="k-external">External id</label>
          <input id="k-external" className={input} value={form.externalId} onChange={(e) => set("externalId", e.target.value)} />
        </div>
      </div>

      <div className="mt-4 grid grid-cols-2 gap-4">
        <div>
          <label className={label} htmlFor="k-title">Title</label>
          <input id="k-title" className={input} value={form.title} onChange={(e) => set("title", e.target.value)} />
        </div>
        <div>
          <label className={label} htmlFor="k-type">Document type</label>
          <input id="k-type" className={input} value={form.documentType} onChange={(e) => set("documentType", e.target.value)} />
        </div>
      </div>

      <div className="mt-4 grid grid-cols-2 gap-4">
        <div>
          <label className={label} htmlFor="k-class">Classification</label>
          <select id="k-class" className={input} value={form.classification} onChange={(e) => set("classification", e.target.value)}>
            {CLASSIFICATIONS.map((c) => (
              <option key={c} value={c}>{c}</option>
            ))}
          </select>
        </div>
        <div>
          <label className={label} htmlFor="k-acl">ACL roles (comma-separated, blank = all)</label>
          <input id="k-acl" className={input} value={aclText} onChange={(e) => setAclText(e.target.value)} placeholder="EMPLOYEE, SUPPORT_AGENT" />
        </div>
      </div>

      <div className="mt-4">
        <label className={label} htmlFor="k-content">Content</label>
        <textarea id="k-content" rows={10} className={`${input} font-mono`} value={form.rawContent} onChange={(e) => set("rawContent", e.target.value)} />
      </div>

      <label className="mt-3 flex items-center gap-2 text-sm text-ink">
        <input type="checkbox" checked={form.extractGraph} onChange={(e) => set("extractGraph", e.target.checked)} />
        Extract entities into the knowledge graph
      </label>

      <button
        type="submit"
        disabled={!canSubmit}
        className="mt-5 rounded-md bg-ink px-4 py-2 text-sm font-medium text-surface disabled:cursor-not-allowed disabled:opacity-60"
      >
        {mutation.isPending ? "Ingesting…" : "Ingest document"}
      </button>
    </form>
  );
}
