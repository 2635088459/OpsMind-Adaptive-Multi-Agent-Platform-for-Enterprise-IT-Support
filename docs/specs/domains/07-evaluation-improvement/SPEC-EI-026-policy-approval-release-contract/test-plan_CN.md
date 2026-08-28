# Test Plan — SPEC-EI-026

## Unit Tests

- domain state transition 和 invariant；
- input validation、version/hash/source linkage；
- idempotency conflict 和 duplicate command；
- `policy approval release contract` 的 happy path 与 failure path。

## Integration Tests

- PostgreSQL migration、唯一键、索引和 JSONB 字段；
- business state + audit + outbox 同事务；
- worker/retry/replay 或 adapter failure；
- sensitive evidence redaction。

## Contract Tests

- 与本 spec 涉及的 01/02/03/04/05/06/08 API 或 event schema 对齐；
- invalid payload、missing evidence、duplicate event、stale version 必须失败；
- contract drift 必须让 CI failed。

## Security/Recovery Tests

- 未授权 actor 被拒绝；
- raw secret 不进入 dataset/report/log/event；
- dependency outage 时 offline gate fail closed；
- processed event/outbox replay 不产生重复 final state。
