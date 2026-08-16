# SPEC-MK-030 Domain Rules

## Rules

- All admin actions audited and authorized.
- Active long-term memory must come from the governed pipeline.
- Candidate, MemoryVersion, KnowledgeDocument, and GraphEdge must have source/evidence.
- Graph is a retrieval explanation layer, not a business state machine.
- Deleted or deprecated objects are excluded from default retrieval.
- PII/secrets/raw tool output must not enter active memory, chunks, logs, or retrieval responses.
