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

/** tool-integration-gateway — SPEC-SC-006's 3rd aggregation source. Real endpoint lives under an `/internal/` prefix with no auth/CORS wired for browser access yet (see that spec's own honest-scope note) — kept here for when that changes. */
export const TOOL_INTEGRATION_GATEWAY_BASE_URL: string = import.meta.env.VITE_TOOL_INTEGRATION_GATEWAY_BASE_URL ?? "http://localhost:8020";
