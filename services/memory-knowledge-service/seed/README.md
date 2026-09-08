# IT-support RAG seed corpus

Plain-markdown knowledge documents for the shared organization-wide retrieval
knowledge base (04-memory-knowledge-service). This is the "reference material"
half of the service — `KnowledgeDocument` + chunks, not distilled `Memory`
rows — and it is what the employee conversational path
(`agent-runtime-service` → `POST /internal/memory/v1/search`, `requester_type=EMPLOYEE`,
`classification=INTERNAL`) retrieves against.

## Loading it

```
scripts/seed-knowledge-base.sh
# or against a non-default host:
MK_BASE_URL=http://localhost:8010 scripts/seed-knowledge-base.sh
```

The script POSTs each file in `manifest.json` to
`POST /internal/memory/v1/admin/documents` (which runs parse → chunk → embed →
index → ACTIVE synchronously), then runs a set of retrieval probes and fails if
any document errors or any probe comes back empty. It is also a step in
`.github/workflows/frontend-e2e.yml`.

`(source_system, external_id, version)` is the ingestion idempotency natural
key, so re-running is a no-op for unchanged files. If you edit a document's
content, add a per-doc `"version"` (one higher than its last) to that entry in
`manifest.json`, or the re-ingest returns HTTP 409. The top-level `"version"`
is the default for entries with no override.

Note: the service has no "supersede prior version" step, so a bumped version's
chunks join the retrieval pool *alongside* the old version's until the old rows
are removed by hand. While iterating on a document that has not been committed
or deployed yet, prefer deleting its rows and re-ingesting at the same version
over bumping.

## Authoring rules

- **Markdown only.** The chunker splits on `#`..`######` headings and blank
  lines; each section between headings becomes one retrievable chunk. Heading
  text itself is *not* stored or matched — keep the searchable words in the
  paragraph body.
- **Each section must stand alone.** The whole chunk is returned verbatim as the
  answer snippet, so it has to make sense out of context.
- **Write the words employees type.** Retrieval in the default offline mode is
  lexical keyword containment (no embeddings), so "vpn won't connect", "reset
  password", "mailbox full" beat formal phrasing.
- **Classification `INTERNAL`, empty `acl`.** That is the only combination the
  `role=EMPLOYEE` conversational path can read. `CONFIDENTIAL`/`RESTRICTED` or a
  non-empty ACL would hide the document from employees.

## Real semantic retrieval

Set `EMBEDDING_PROVIDER=openai` plus a real `OPENAI_API_KEY` in the compose
`.env` to additionally wire OpenAI `text-embedding-3-small` and the pgvector
cosine-similarity path. Without a key the service stays on lexical keyword-only
retrieval (it does not fall through to garbage-scored hash-vector results).
