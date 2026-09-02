/**
 * The one place this app reads its backend-base-URL env vars. Missing values
 * default to each service's own documented local-dev port (never throwing),
 * so a fresh checkout without a `.env` still runs against the platform's own
 * documented local-dev defaults.
 */

/** user-access-authentication-service — SecurityConfig#browserLoginFilterChain, BrowserSessionTokenController. */
export const BFF_BASE_URL: string = import.meta.env.VITE_BFF_BASE_URL ?? "http://localhost:8087";

/** ticket-workflow-service — ticket/api/support (SupportTicketQueryController, TriageTicketController, TicketAssignmentController, TransitionTicketStatusController, ResolveTicketController). */
export const TICKET_WORKFLOW_BASE_URL: string = import.meta.env.VITE_TICKET_WORKFLOW_BASE_URL ?? "http://localhost:8080";

/** policy-approval-governance-service — GovernanceAuditController, ApprovalController. */
export const POLICY_APPROVAL_GOVERNANCE_BASE_URL: string = import.meta.env.VITE_POLICY_APPROVAL_GOVERNANCE_BASE_URL ?? "http://localhost:8086";

/** tool-integration-gateway — SPEC-SC-006's 3rd aggregation source. Real auth (X-Caller-Id/X-Caller-Type on writes) + real CORS (GET-only) were added there for real by SPEC-SC-018/020's own follow-up hardening; this app's own read is genuinely reachable now (see fetchToolRequestEntries's own comment for the separate, still-open agent-runtime-service gap). */
export const TOOL_INTEGRATION_GATEWAY_BASE_URL: string = import.meta.env.VITE_TOOL_INTEGRATION_GATEWAY_BASE_URL ?? "http://localhost:8020";

/** evaluation-improvement-service — SPEC-SC-015's real run/scores/regression-report read endpoints (`/evaluation/runs/...`). This app is its first browser caller; CORS was added there for real, this session. */
export const EVALUATION_IMPROVEMENT_BASE_URL: string = import.meta.env.VITE_EVALUATION_IMPROVEMENT_BASE_URL ?? "http://localhost:8011";
