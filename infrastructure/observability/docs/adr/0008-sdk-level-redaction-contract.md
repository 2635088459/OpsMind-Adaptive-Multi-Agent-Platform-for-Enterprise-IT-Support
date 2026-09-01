# ADR-0008: SDK-level redaction is a documented producer contract, not domain-08-owned code

> Status: Accepted
> Date: 2026-09-01
> Spec: SPEC-OP-031
> Deciders: platform-observability

## Context

`SPEC-OP-031`'s stated objective is "redact at SDK **and** Collector" — two layers,
not one. The Collector layer is real and has been since `SPEC-OP-003`
(`transform/governance` deny-list) and `SPEC-OP-007`
(`transform/log-body-redaction`, verified live under this same spec — a bearer
token and an email address embedded in a free-text log body both landed in Loki
already replaced with `[REDACTED]` / `[REDACTED_EMAIL]`, stamped
`opsmind.log.redacted`). The SDK layer had no equivalent artifact anywhere in the
repository.

Domain-08's own domain rules (`domain-rules.md`, every phase) state: "Signals are
immutable observations and preserve source-domain ownership." Concretely, that has
always meant domain-08 does not reach into another domain's application code —
every earlier phase's producer-side work (e.g. `SPEC-OP-025`+ OTLP exporter
bootstrap) was scoped as *this domain documents the contract; each producer domain
implements it against its own stack*. `SPEC-OP-031` does not carry an exception to
that boundary, and this session confirmed it directly: domain 01
(`user-access-authentication-service`, Java/Spring) has real
`micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` dependencies
wired (`pom.xml`), a real `TenantId` domain concept
(`domain/shared/TenantId.java`), and a real `ObservabilityConfig` — but zero
Baggage or span-attribute redaction code; its own request-scoped PII (e.g.
`correlation_id`) is read straight off an HTTP header
(`IdentityRequestContext.java`), not carried as OTel Baggage. Writing redaction
logic directly into that service (or any other domain's) would be domain-08
authoring another domain's application code with no LLD mapping asking for it —
exactly the kind of unrequested cross-domain edit the roadmap's established
practice (see `SPEC-TG-0xx`'s closing note) says to avoid.

## Decision

- **SDK-level redaction is real, but its artifact is a contract, not code**: a
  `docs/producer-sdk-redaction-contract.md` under this domain's own docs, stating
  what every producer SDK bootstrap must do before a span/log/metric leaves the
  process boundary: never place a secret, credential, or raw PII value into a
  span/log attribute or a free-text log body in the first place; treat the
  Collector's `transform/governance` deny-list and
  `governance/telemetry-governance.yaml : log_body_redaction` patterns as the
  documented, versioned floor a producer's own instrumentation is checked against.
- **The Collector remains the enforced backstop**, not merely a redundant second
  check: SDK-side redaction cannot be verified by domain-08 (it runs inside code
  domain-08 doesn't own or test), so the Collector-side deny-list/body-redaction
  is what `validate-signal-contracts.py`'s fixtures and this spec's own live
  verification actually hold accountable. This mirrors `SPEC-OP-007`'s own
  layering: SDK discipline is the first, best-effort line; the Collector is the
  line that is actually enforced and tested.
- No domain-01..07 application file is modified by this spec. If a future spec
  needs a producer domain to add real Baggage/redaction code, that is that
  producer domain's own spec to carry (matching the resolution already reached
  for domain 01's `tenant.id` baggage gap under `SPEC-OP-031`'s tenant-isolation
  decision, recorded in this spec's own traceability doc).

## Consequences

- "Redact at SDK" is honestly a documentation/contract deliverable this cycle, not
  a second enforced technical control — the traceability doc records this as a
  residual limitation, not a completed guarantee.
- A producer that never reads the contract can still leak a raw secret into a log
  body; the Collector's `transform/log-body-redaction` patterns are the actual
  safety net, and are only as good as the pattern list in
  `telemetry-governance.yaml` (already known to be a fixed, reviewed list — not a
  general-purpose scanner).

## Alternatives considered

- **Add real Baggage-based redaction code to domain 01 (or all domains) as part of
  this spec.** Rejected: no LLD section maps this spec to editing another
  domain's application code, and this domain's own established boundary
  (confirmed repeatedly across `SPEC-TG-0xx`'s 32-spec closing note) is to
  document contracts other domains implement, not implement them here. Doing so
  unilaterally on an ambiguous, consequential call is exactly what past
  practice says to avoid.
- **Skip the SDK layer entirely, only claim Collector-level redaction.** Rejected:
  the spec's own goal explicitly names both layers; narrowing scope silently
  (rather than documenting the real, honest shape of what "SDK redaction" means
  here) would misrepresent what was actually verified.
