# Producer SDK Redaction Contract

> owner: platform-observability
> spec: SPEC-OP-031 (see ADR-0008 for why this is a doc, not code, in this domain)
> audience: every producer domain (01–07) instrumenting OTel spans/logs/metrics

## What every producer SDK bootstrap must do

Before a span, log record, or metric datapoint leaves your service's process
boundary (i.e. before it reaches the Collector's `otlp` receiver), your own
instrumentation code — not the Collector — is your first and best line of
defense. Concretely:

1. **Never place a secret, credential, token, or session identifier into a
   span/log attribute, a log body, or a metric label.** This includes
   `Authorization` header values, API keys, database passwords, session
   cookies, MFA codes/seeds, and JWTs — the exact set the Collector's
   `transform/governance` deny-list already deletes by key name
   (`infrastructure/observability/governance/telemetry-governance.yaml :
   deny_fields`). If your key name matches that list, the Collector removes it
   — but the Collector cannot know the *value* is sensitive if you put it under
   an innocuous-looking key.
2. **Never place raw user-submitted text, prompts, or LLM completions into a
   span/log attribute or log body.** Redacted/aggregated views (counts,
   lengths, classifications) are fine; the raw content is not — this is a
   deny-listed key pattern (`gen_ai.(prompt|completion).*`,
   `(message|user)[_-]?(content|text|body)`) for the same reason.
3. **Never place raw PII (email, phone, SSN, credit-card number) into a
   free-text log body as a matter of course.** The Collector's
   `transform/log-body-redaction` processor (`SPEC-OP-007`) pattern-matches and
   replaces the known shapes in
   `governance/telemetry-governance.yaml : log_body_redaction.patterns` as a
   backstop — verified live under `SPEC-OP-031` (a bearer token and an email
   embedded in a log body both landed in Loki already replaced with
   `[REDACTED]` / `[REDACTED_EMAIL]`) — but a backstop is not a design target.
   Structured, non-PII identifiers (a user ID, a tenant slug) are the
   correct thing to log instead.
4. **Treat the deny-list and redaction pattern list as a versioned contract you
   can check your own output against**, not an implementation detail of this
   domain. Both live in one file
   (`governance/telemetry-governance.yaml`), are SemVer-versioned, and are
   validated for internal consistency by `scripts/validate-telemetry-governance.py`
   and `scripts/validate-signal-contracts.py` on every change.

## What this contract does NOT claim

- This is **not** an enforced technical control from domain-08's side. No CI in
  this domain runs against your service's source code; nothing here fails your
  build if you violate it. The Collector-side deny-list/redaction is what is
  actually tested (via `scripts/validate-signal-contracts.py`'s fixtures and
  this spec's own live docker-compose verification) and is the real backstop if
  SDK-side discipline lapses.
- This is **not** a general-purpose PII/secret scanner. The redaction patterns
  are a fixed, reviewed list (bearer tokens, JWTs, email addresses, credit-card
  shapes today) — a value that doesn't match one of those shapes will reach the
  backend unredacted regardless of which layer was supposed to catch it.

## Where to look

- Deny-list (key-based, all signal types): `governance/telemetry-governance.yaml : deny_fields`
- Log body redaction (value-based, logs only): `governance/telemetry-governance.yaml : log_body_redaction`
- Collector enforcement: `collector/base/config.yaml : transform/governance`, `transform/log-body-redaction`
- Why this is a doc and not code in another domain's repo: `docs/adr/0008-sdk-level-redaction-contract.md`
