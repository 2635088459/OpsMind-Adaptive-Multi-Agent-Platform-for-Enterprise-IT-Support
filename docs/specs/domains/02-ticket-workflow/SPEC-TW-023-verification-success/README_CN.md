# SPEC-TW-023 — Verification Success（验证成功）

## 1. 目标

消费 trusted `verification.completed.v1` 成功结果，验证其属于当前 Ticket、workflow、resolution cycle 和 verification attempt，并保存 trusted verification evidence。

本 SPEC 只应用验证成功并标记 resolution-ready；真正进入 `RESOLVED` 由 `SPEC-TW-025` 完成。

## 2. 范围

包含 event consumer、producer/schema 校验、引用匹配、evidence snapshot、duplicate/stale 分类、`ticket.verification-success-applied.v1`。

不包含人工 resolution summary、close、reopen。
