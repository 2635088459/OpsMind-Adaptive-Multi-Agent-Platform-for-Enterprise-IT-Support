# SPEC-MK-001 Domain Rules

## Rules

- No Ticket/Workflow/Tool state ownership; no direct tool execution; no cross-domain transactions.
- Active long-term memory must come from the governed pipeline.
- Candidate, MemoryVersion, KnowledgeDocument, and GraphEdge must have source/evidence.
- Graph is a retrieval explanation layer, not a business state machine.
- Deleted or deprecated objects are excluded from default retrieval.
- PII/secrets/raw tool output must not enter active memory, chunks, logs, or retrieval responses.
