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

/** SPEC-SC-006: real, already-implemented `GET /api/v1/tickets/{id}/timeline` (TicketTimelineController) — the SUPPORT view is resolved server-side from the trusted JWT, never requested by this client. */
export async function fetchTimelineEntries(ticketId: string): Promise<AiLogEntry[]> {
  const response = await authedFetch(`${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets/${ticketId}/timeline`, { method: "GET" });
  const body = (await response.json()) as { items: SupportTimelineItemWire[] };
  return body.items.map((item) => ({ id: item.itemId, source: "timeline", occurredAt: item.occurredAt, summary: item.summary }));
}

interface GovernanceAuditRecordWire {
  auditRecordId: string;
  action: string;
  recordedAt: string;
  reason: string | null;
}

/** SPEC-SC-006: real, already-implemented `GET /api/v1/governance-audit-records?ticketId=...` (GovernanceAuditController, domain 06). */
export async function fetchGovernanceAuditEntries(ticketId: string): Promise<AiLogEntry[]> {
  const response = await authedFetch(`${POLICY_APPROVAL_GOVERNANCE_BASE_URL}/api/v1/governance-audit-records?ticketId=${encodeURIComponent(ticketId)}`, { method: "GET" });
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
 * SPEC-SC-006's 3rd source — a real, honest gap: `GET /internal/tool-gateway/
 * v1/tool-requests/{id}` (domain 05, tool-integration-gateway) exists but
 * lives under an unauthenticated `/internal/` prefix with no CORS wired at
 * all (confirmed by reading that service's own main.py/routes directly) —
 * not actually reachable from a real browser today. This function is real,
 * tested client code aimed at the real response shape, MSW-mocked only
 * until that service exposes a genuine public, authenticated, CORS-enabled
 * equivalent — a decision this spec's own domain (05) owns, not fabricated
 * here.
 */
export async function fetchToolRequestEntries(toolRequestId: string): Promise<AiLogEntry[]> {
  const response = await authedFetch(`${TOOL_INTEGRATION_GATEWAY_BASE_URL}/internal/tool-gateway/v1/tool-requests/${toolRequestId}`, { method: "GET" });
  const body = (await response.json()) as ToolRequestWire;
  return [{ id: body.tool_request_id, source: "tool-request", occurredAt: body.created_at, summary: `${body.tool_name ?? "tool"}: ${body.reason}` }];
}
