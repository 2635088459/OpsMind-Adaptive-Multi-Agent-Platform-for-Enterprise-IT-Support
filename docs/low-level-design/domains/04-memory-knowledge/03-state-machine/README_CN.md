# 03 State Machine

## Memory Candidate 状态机

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

规则：

- `EXTRACTED -> REDACTED` 必须生成 redaction report。
- `REDACTED -> VALIDATED` 必须校验 source refs 存在且可信。
- `VALIDATED -> DUPLICATE` 必须记录 duplicateOfMemoryId。
- `VALIDATED -> CONFLICTING` 必须记录 conflictSetId。
- `APPROVED -> PUBLISHED` 必须在同一事务中创建 MemoryVersion 和 outbox event。

## Memory Version 状态机

```text
DRAFT -> ACTIVE
ACTIVE -> SUPERSEDED
ACTIVE -> DEPRECATED
ACTIVE -> DELETED
SUPERSEDED -> DELETED
DEPRECATED -> DELETED
```

规则：

- 同一个 `memoryId` 同时只能有一个 `ACTIVE` version。
- `SUPERSEDED` 必须指向新 active version。
- `DELETED` 不可检索，但保留 audit metadata。
- `DEPRECATED` 可在 admin 查询中可见，默认不进入 Agent retrieval。

## Knowledge Document Ingestion 状态机

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

规则：

- `PARSED` 之前不能生成 chunk。
- `CHUNKED` 后 chunks 不可原地修改。
- `EMBEDDED` 可失败并重试。
- `INDEXED -> ACTIVE` 后才可被检索。

## Working Memory 状态

Working Memory 不需要复杂状态机，使用 `ACTIVE / ARCHIVED / DELETED`。

- ticket cycle 结束后可 `ARCHIVED`。
- reopen 新 cycle 时创建新的 WorkingMemory。
- deletion request 可把 body 清空并保留 tombstone。

## Deletion 状态机

```text
REQUESTED -> AUTHORIZED -> APPLIED -> VERIFIED
REQUESTED -> REJECTED
AUTHORIZED -> FAILED_RETRYABLE
FAILED_RETRYABLE -> APPLIED
```

删除必须覆盖：memory content、memory versions、embeddings、document chunks、retrieval visibility 和 cache。
