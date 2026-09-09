/**
 * The one place this app reads its backend-base-URL env vars. Missing values
 * default to each service's own documented local-dev port (never throwing),
 * so a fresh checkout without a `.env` still runs against the platform's own
 * documented local-dev defaults.
 */

/** user-access-authentication-service — SecurityConfig#browserLoginFilterChain, BrowserSessionTokenController. */
export const BFF_BASE_URL: string = import.meta.env.VITE_BFF_BASE_URL ?? "http://localhost:8087";

/** ticket-workflow-service — ticket/api/support (SupportTicketQueryController, TriageTicketController, TicketAssignmentController, TransitionTicketStatusController, ResolveTicketController). Host port moved off :8080 to :18080 (2026-09-02) — see full-platform.yml's own ticket-workflow-service port comment for why (freed :8080 for a real browser's Keycloak login). */
export const TICKET_WORKFLOW_BASE_URL: string = import.meta.env.VITE_TICKET_WORKFLOW_BASE_URL ?? "http://localhost:18080";

/** policy-approval-governance-service — GovernanceAuditController, ApprovalController. */
export const POLICY_APPROVAL_GOVERNANCE_BASE_URL: string = import.meta.env.VITE_POLICY_APPROVAL_GOVERNANCE_BASE_URL ?? "http://localhost:8086";

/** tool-integration-gateway — SPEC-SC-006's 3rd aggregation source. Real auth (X-Caller-Id/X-Caller-Type on writes) + real CORS (GET-only) were added there for real by SPEC-SC-018/020's own follow-up hardening; this app's own read is genuinely reachable now (see fetchToolRequestEntries's own comment for the separate, still-open agent-runtime-service gap). */
export const TOOL_INTEGRATION_GATEWAY_BASE_URL: string = import.meta.env.VITE_TOOL_INTEGRATION_GATEWAY_BASE_URL ?? "http://localhost:8020";

/** evaluation-improvement-service — SPEC-SC-015's real run/scores/regression-report read endpoints (`/evaluation/runs/...`). This app is its first browser caller; CORS was added there for real, this session. */
export const EVALUATION_IMPROVEMENT_BASE_URL: string = import.meta.env.VITE_EVALUATION_IMPROVEMENT_BASE_URL ?? "http://localhost:8011";

/** memory-knowledge-service — the admin knowledge-document ingest surface (`/internal/memory/v1/admin/...`). CORS for this browser origin was opened alongside the admin UI. */
export const MEMORY_KNOWLEDGE_BASE_URL: string = import.meta.env.VITE_MEMORY_KNOWLEDGE_BASE_URL ?? "http://localhost:8010";

/**
 * Grafana host for the trace deep-link (`05-api-contracts` §"Trace deep link":
 * `https://{grafana-host}/explore?...traceID={traceId}`). Per UC-SC-05 the
 * console renders only a preview waterfall in-viewport; real troubleshooting
 * always links out to Tempo's own UI here.
 */
export const GRAFANA_BASE_URL: string = import.meta.env.VITE_GRAFANA_BASE_URL ?? "http://localhost:3000";

/** LangSmith web host for the "view the full experiment" link-out (UC-SC-06). This console never calls LangSmith itself — it only builds an outbound URL. */
export const LANGSMITH_BASE_URL: string = import.meta.env.VITE_LANGSMITH_BASE_URL ?? "https://smith.langchain.com";

/**
 * LangSmith workspace/org id, needed to build a project deep link
 * (`{base}/o/{org}/projects/p/{projectId}`). Empty when LangSmith isn't wired
 * for this environment — the "View in LangSmith" link is then hidden even for a
 * run that has an experiment ref, since the URL can't be built without it.
 */
export const LANGSMITH_ORG_ID: string = import.meta.env.VITE_LANGSMITH_ORG_ID ?? "";
