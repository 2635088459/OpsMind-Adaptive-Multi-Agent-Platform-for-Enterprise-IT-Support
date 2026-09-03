/**
 * The one place this app reads its backend-base-URL env vars. Missing values
 * default to each service's own documented local-dev port (never throwing),
 * so a fresh checkout without a `.env` still runs against the platform's own
 * documented local-dev defaults.
 */

/** user-access-authentication-service — SecurityConfig#browserLoginFilterChain, BrowserSessionTokenController. */
export const BFF_BASE_URL: string = import.meta.env.VITE_BFF_BASE_URL ?? "http://localhost:8087";

/** agent-runtime-service — interfaces/conversation/router.py (SPEC-ARO-038~042). */
export const AGENT_RUNTIME_BASE_URL: string = import.meta.env.VITE_AGENT_RUNTIME_BASE_URL ?? "http://localhost:8000";

/** ticket-workflow-service — ticket/api/publicapi (PublicTicketQueryController, ConfirmResolutionController, RequesterReopenTicketController). Host port moved off :8080 to :18080 (2026-09-02) — see full-platform.yml's own ticket-workflow-service port comment for why (freed :8080 for a real browser's Keycloak login). */
export const TICKET_WORKFLOW_BASE_URL: string = import.meta.env.VITE_TICKET_WORKFLOW_BASE_URL ?? "http://localhost:18080";

/** attachment-service — SPEC-EP-010/011's own shared attachments capability, now real (AttachmentController, port 8090). */
export const ATTACHMENTS_BASE_URL: string = import.meta.env.VITE_ATTACHMENTS_BASE_URL ?? "http://localhost:8090";
