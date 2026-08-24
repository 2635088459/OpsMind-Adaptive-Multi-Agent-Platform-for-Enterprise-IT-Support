# 11 Security

## Tokens and protocols

- Use Authorization Code + PKCE with short-lived one-time state and nonce; implicit and password grants are forbidden.
- Each issuer has fixed discovery URL, allowed algorithms, audiences, and token types. Reject `alg=none`, algorithm confusion, and arbitrary `jku/x5u`.
- Browser sessions use Secure, HttpOnly, SameSite cookies and CSRF protection; callback URLs are exact allowlists.
- Workloads use client credentials or mTLS with separate audiences/scopes and cannot impersonate a human `sub`.

## Authorization and data

- Controller, application use case, and repository query all enforce tenant/scope. RAG/SQL filters before reading; an LLM never decides after unauthorized retrieval.
- User text, agent output, and self-asserted authorization are untrusted. Domain 01 accepts only verified tokens and server-side mappings.
- Step-up uses opaque handle/hash bound to action/resource/session and consumed once; domain 06 still owns approval and separation of duties.
- Secrets enter through secret manager/environment injection and never appear in source configuration, logs, traces, events, tickets, memory, or prompts.

## Attack resistance and privacy

- Login, callback, introspection, step-up, and admin APIs are rate-limited by subject/IP/client; anomalies emit security events.
- Schema validation, mass-assignment prevention, parameterized SQL, and fixed outbound hosts mitigate injection and SSRF.
- PII is classified, masked/encrypted by field, minimized in responses, retained/deleted by policy; audit access is itself audited.
- Break-glass requires strong authentication, domain-06 approval/dual control, bounded scope/time, and non-disableable audit.

Threat modeling covers token substitution/replay/theft, session fixation, CSRF, open redirect, JWKS poisoning, confused deputy, horizontal/vertical escalation, tenant escape, PII leakage, and malicious IdP/events.

---

> Domain: `01-user-access-authentication`  
> Service: `user-access-authentication-service`  
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`  
> Status: Detailed LLD  
> Spec mapping: `SPEC-UA-004, SPEC-UA-005, SPEC-UA-006, SPEC-UA-010, SPEC-UA-013, SPEC-UA-015, SPEC-UA-016, SPEC-UA-018, SPEC-UA-019, SPEC-UA-031, SPEC-UA-034`
