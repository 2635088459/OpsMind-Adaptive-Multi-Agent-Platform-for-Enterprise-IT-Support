package com.opsmind.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * SPEC-SC-014 (domain 10's trace-waterfall preview). This service acts as
 * an authenticated proxy in front of domain 08's real Tempo query API
 * (`08-observability-platform`, SPEC-OP-002/014) — chosen over letting the
 * browser call Tempo directly because domain 08's own security doc
 * (`ObservabilityAccessControl.md`, SPEC-OP-030) states plainly that
 * Tempo/Loki's query APIs were deliberately left with NO authentication
 * (no shared Keycloak existed to gate them against at the time); nothing in
 * this platform pointed a browser at that surface before this spec, and
 * this proxy is what keeps it that way.
 *
 * <p>{@code baseUrl} defaults to {@code host.docker.internal} rather than
 * joining the `opsmind-observability` Compose network directly: that
 * network is a SEPARATE Compose project (`observability-stack.yml`'s own
 * explicit design — "independent of local-platform.yml, can run alongside
 * it") from this service's own `local-platform.yml`/`full-platform.yml`
 * network. Declaring it as an `external` dependency would mean THIS
 * service — the platform's own critical-path authentication gateway —
 * fails to start whenever the (optional, frequently-not-running)
 * observability stack happens to be down. Reaching it via the host's own
 * published port keeps the two stacks' startup fully decoupled; a missing/
 * unreachable Tempo surfaces as an honest per-request 503 ({@link
 * com.opsmind.identity.application.exception.TraceQueryUnavailableException})
 * rather than blocking this service's own boot. `host.docker.internal` is a
 * Docker-Desktop convenience (works on this repo's actual dev environment,
 * macOS); a Linux deployment would need either `extra_hosts:
 * host-gateway` or to set this property to a real reachable address.
 *
 * <p>{@code tenants}: SPEC-OP-031's own real per-producing-domain Tempo
 * multi-tenancy means a single ticket-processing trace's spans can be
 * split across several tenants (ADR-0011, `08-observability-platform`) —
 * OSS Tempo has no cross-tenant query. This fixed list is every producing
 * domain plausibly touched by an AI-processed ticket's own trace
 * (confirmed against the Collector's own `routing/traces-tenant`
 * connector, `infrastructure/observability/collector/base/config.yaml`) —
 * deliberately excludes `user-access-authentication`/`evaluation-
 * improvement`/`observability-platform`, which are real tenants but not
 * part of this business flow.
 */
@ConfigurationProperties(prefix = "app.identity.observability.tempo")
public record TempoQueryProperties(String baseUrl, List<String> tenants) {

    public TempoQueryProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://host.docker.internal:3200";
        }
        tenants = (tenants == null || tenants.isEmpty())
            ? List.of("ticket-workflow", "agent-runtime", "tool-integration", "policy-approval-governance", "memory-knowledge", "shared")
            : List.copyOf(tenants);
    }
}
