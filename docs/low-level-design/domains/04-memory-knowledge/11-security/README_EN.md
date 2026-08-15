# 11 Security

## Access Control

Search requests must include:

- requester type;
- requester role;
- ticket id / workflow id;
- tenant / application / queue scope;
- correlation id.

Before retrieval, the service computes access scope and applies it to:

- Memory classification;
- Knowledge Document ACL;
- source ticket visibility;
- role capability such as `knowledge_base_read`.

## Data Protection

Content forbidden in long-term memory:

- password, token, API key, cookie, session id;
- full SSN, DOB, personal email, phone number;
- sensitive fields from raw tool responses;
- unredacted user input;
- full high-sensitivity approval rationale.

Allowed content:

- redacted summary;
- hashed identifier;
- source reference;
- evidence checksum;
- classification label;
- minimal operational fact.

## Redaction Pipeline

1. Pattern redaction.
2. Structured field redaction.
3. Policy rule redaction.
4. Human review for high-risk candidates.
5. Persist RedactionReport.

## Prompt-Injection Defense

Knowledge documents and memories are untrusted input:

- Results returned to Agents must be labeled with source type.
- Document content cannot override system/developer/runtime instructions.
- Retrieval result cannot trigger tool execution.
- Agents must consume retrieved content through Runtime's context builder.

## Audit

Audit is required for:

- admin document ingestion;
- candidate approve / reject;
- memory publish / supersede / delete;
- retention override;
- access denied;
- high-sensitivity search.

## Deletion and Retention

Deletion requests require authorization. After deletion, keep tombstone, audit, and source hash by default, but do not keep recoverable original content.
