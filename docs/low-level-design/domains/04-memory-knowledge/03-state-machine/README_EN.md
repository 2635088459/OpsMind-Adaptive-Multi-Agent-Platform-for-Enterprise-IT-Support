# 03 State Machine

## Memory Candidate State Machine

```text
EXTRACTED
  -> REDACTED
  -> VALIDATED
  -> APPROVED
  -> PUBLISHED

EXTRACTED / REDACTED / VALIDATED
  -> REJECTED

VALIDATED
  -> DUPLICATE
  -> CONFLICTING

CONFLICTING
  -> APPROVED
  -> REJECTED
```

Rules:

- `EXTRACTED -> REDACTED` must produce a redaction report.
- `REDACTED -> VALIDATED` must verify that source refs exist and are trusted.
- `VALIDATED -> DUPLICATE` must record `duplicateOfMemoryId`.
- `VALIDATED -> CONFLICTING` must record `conflictSetId`.
- `APPROVED -> PUBLISHED` must create the MemoryVersion and outbox event in the same transaction.

## Memory Version State Machine

```text
DRAFT -> ACTIVE
ACTIVE -> SUPERSEDED
ACTIVE -> DEPRECATED
ACTIVE -> DELETED
SUPERSEDED -> DELETED
DEPRECATED -> DELETED
```

Rules:

- A `memoryId` may have only one `ACTIVE` version at a time.
- `SUPERSEDED` must point to the new active version.
- `DELETED` is not retrievable but keeps audit metadata.
- `DEPRECATED` is visible to admin queries but excluded from default Agent retrieval.

## Knowledge Document Ingestion State Machine

```text
RECEIVED
  -> PARSED
  -> CHUNKED
  -> EMBEDDED
  -> INDEXED
  -> ACTIVE

RECEIVED / PARSED / CHUNKED / EMBEDDED
  -> FAILED

ACTIVE
  -> SUPERSEDED
  -> EXPIRED
  -> DELETED
```

Rules:

- Chunks cannot be generated before `PARSED`.
- Chunks are immutable after `CHUNKED`.
- `EMBEDDED` may fail and be retried.
- Documents are searchable only after `INDEXED -> ACTIVE`.

## Working Memory States

Working Memory uses only `ACTIVE / ARCHIVED / DELETED`.

- A completed ticket cycle may archive its WorkingMemory.
- A reopened ticket cycle creates a new WorkingMemory.
- A deletion request may clear the body while retaining a tombstone.

## Graph Index States

Graph nodes / edges keep a lightweight state:

```text
VISIBLE -> HIDDEN
VISIBLE -> TOMBSTONED
HIDDEN -> VISIBLE
```

Rules:

- `VISIBLE`: participates in search expansion.
- `HIDDEN`: retained but excluded from default retrieval, for example when its source document is deprecated.
- `TOMBSTONED`: no longer retrievable after deletion or retention; only audit metadata remains.
- When a MemoryVersion is superseded, a `SUPERSEDES` edge is added and the old version node becomes `HIDDEN` by default.
- Document reingestion creates new chunk nodes for the new version; old versions are not updated in place.

## Deletion State Machine

```text
REQUESTED -> AUTHORIZED -> APPLIED -> VERIFIED
REQUESTED -> REJECTED
AUTHORIZED -> FAILED_RETRYABLE
FAILED_RETRYABLE -> APPLIED
```

Deletion must cover memory content, memory versions, embeddings, document chunks, retrieval visibility, and caches.
