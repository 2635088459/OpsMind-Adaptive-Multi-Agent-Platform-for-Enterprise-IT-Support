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

/** ticket-workflow-service — ticket/api/publicapi (PublicTicketQueryController, ConfirmResolutionController, RequesterReopenTicketController). */
export const TICKET_WORKFLOW_BASE_URL: string = import.meta.env.VITE_TICKET_WORKFLOW_BASE_URL ?? "http://localhost:8080";

/** SPEC-EP-010: the new shared attachments capability — chartered but not yet designed/built anywhere in this platform (empty default, deliberately never fabricated). */
export const ATTACHMENTS_BASE_URL: string = import.meta.env.VITE_ATTACHMENTS_BASE_URL ?? "";
