import { authedFetch } from "@/lib/httpClient";
import { POLICY_APPROVAL_GOVERNANCE_BASE_URL, TICKET_WORKFLOW_BASE_URL, TOOL_INTEGRATION_GATEWAY_BASE_URL } from "@/lib/env";
import type { AiLogEntry } from "@/features/ailog/types";

interface SupportTimelineItemWire {
  itemId: string;
  itemType: string;
  occurredAt: string;
  actor: { type: string; displayLabel: string; actorRef: string | null };
  summary: string;
}

/**
 * SPEC-SC-006: real, already-implemented `GET /api/v1/tickets/{id}/timeline`
 * (TicketTimelineController) — the SUPPORT view is resolved server-side from
 * the trusted JWT, never requested by this client. `traceparent` is an
 * optional override (SPEC-SC-020) so `useAiLog`'s 3 concurrent sources can
 * share one common parent span instead of each getting an unrelated one.
 */
export async function fetchTimelineEntries(ticketId: string, traceparent?: string): Promise<AiLogEntry[]> {
  const response = await authedFetch(`${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets/${ticketId}/timeline`, { method: "GET", headers: traceparent ? { traceparent } : undefined });
  const body = (await response.json()) as { items: SupportTimelineItemWire[] };
  return body.items.map((item) => ({ id: item.itemId, source: "timeline", occurredAt: item.occurredAt, summary: item.summary }));
}

interface GovernanceAuditRecordWire {
  auditRecordId: string;
  action: string;
  recordedAt: string;
  reason: string | null;
}

/** SPEC-SC-006: real, already-implemented `GET /api/v1/governance-audit-records?ticketId=...` (GovernanceAuditController, domain 06). See {@link fetchTimelineEntries} for the `traceparent` override's own reasoning. */
export async function fetchGovernanceAuditEntries(ticketId: string, traceparent?: string): Promise<AiLogEntry[]> {
  const response = await authedFetch(`${POLICY_APPROVAL_GOVERNANCE_BASE_URL}/api/v1/governance-audit-records?ticketId=${encodeURIComponent(ticketId)}`, { method: "GET", headers: traceparent ? { traceparent } : undefined });
  const records = (await response.json()) as GovernanceAuditRecordWire[];
  return records.map((r) => ({ id: r.auditRecordId, source: "governance-audit", occurredAt: r.recordedAt, summary: r.reason ?? r.action }));
}

interface ToolRequestWire {
  tool_request_id: string;
  status: string;
  tool_name: string | null;
  reason: string;
  created_at: string;
}

/**
 * SPEC-SC-006's 3rd source — `GET /internal/tool-gateway/v1/tool-requests/{id}`
 * (domain 05, tool-integration-gateway). Was a real, honest gap (unauthenticated
 * `/internal/` prefix, no CORS) until SPEC-SC-018/020's own follow-up hardening:
 * that service now requires a real `X-Caller-Id`/`X-Caller-Type: SERVICE` pair
 * on every WRITE endpoint (submit/decide/cancel), but this GET stays
 * deliberately open to an unauthenticated caller — this app sends no such
 * headers — and CORS is now real (GET-only; `X-Caller-Id`/`X-Caller-Type`
 * deliberately excluded from the allowed request headers, so a malicious
 * cross-origin page cannot spoof a service caller through a real browser
 * either), live-verified against a running instance.
 *
 * A separate, larger, NOT-fixed-here gap remains: `agent-runtime-service`'s
 * own `ToolGatewayPort` adapter (`LoggingToolGatewayPort`) is still a
 * placeholder that only logs a fake "DISPATCHED" acknowledgement — it never
 * actually calls this real HTTP API, so no real `ToolRequest` row exists for
 * any real ticket today regardless of this fix. This call is genuinely wired
 * and reachable now; it will correctly 404 until domain 03 builds its own
 * real HTTP adapter (its own "phase-05 tool-gateway-mediation", a materially
 * larger, separate undertaking — flagged, not silently expanded into here).
 */
export async function fetchToolRequestEntries(toolRequestId: string, traceparent?: string): Promise<AiLogEntry[]> {
  const response = await authedFetch(`${TOOL_INTEGRATION_GATEWAY_BASE_URL}/internal/tool-gateway/v1/tool-requests/${toolRequestId}`, { method: "GET", headers: traceparent ? { traceparent } : undefined });
  const body = (await response.json()) as ToolRequestWire;
  return [{ id: body.tool_request_id, source: "tool-request", occurredAt: body.created_at, summary: `${body.tool_name ?? "tool"}: ${body.reason}` }];
}
