# SPEC-MK-032 API Contract

## API 范围

本 spec 的 API 以 `release` 能力为边界。若本 spec 不暴露 HTTP API，则必须说明调用方通过 application service 或 event consumer 进入。

## 通用约束

- Internal API 必须携带 correlation id。
- Admin API 必须携带 actor id 并写 audit。
- Search/Context API 只能返回 redacted snippet 和 provenance。
- Runtime 可以读取 evidence，但不能直接写 active memory。
