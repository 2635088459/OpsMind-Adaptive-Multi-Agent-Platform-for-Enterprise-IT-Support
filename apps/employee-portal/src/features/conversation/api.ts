import { authedFetch, newIdempotencyKey } from "@/lib/httpClient";
import { AGENT_RUNTIME_BASE_URL } from "@/lib/env";
import type { ActionOutcome, Conversation, ConversationDetail, MessageTurn } from "@/features/conversation/types";

const BASE = `${AGENT_RUNTIME_BASE_URL}/api/v1/conversations`;

// Wire-shape (snake_case) response interfaces — private to this module;
// every other file in this feature works with the mapped camelCase types
// from types.ts. Field names/values verified by reading agent-runtime-
// service's interfaces/conversation/schemas.py and mapper.py directly, not
// assumed from this domain's own (aspirationally camelCase) spec prose.
interface StartConversationResponseWire {
  conversation_id: string;
  started_at: string;
}

interface MessageTurnResponseWire {
  type: string;
  text?: string | null;
  action_id?: string | null;
  summary?: string | null;
  risk_level?: string | null;
  ticket_id?: string | null;
  display_id?: string | null;
  reason?: string | null;
  assigned_team?: string | null;
}

interface ActionOutcomeResponseWire {
  outcome: string;
}

interface ConversationDetailResponseWire {
  conversation_id: string;
  state: string;
  started_at: string;
  updated_at: string;
}

function toMessageTurn(wire: MessageTurnResponseWire): MessageTurn {
  switch (wire.type) {
    case "text":
      return { kind: "text", text: wire.text ?? "" };
    case "proposedAction":
      return { kind: "proposedAction", actionId: wire.action_id ?? "", summary: wire.summary ?? "", riskLevel: wire.risk_level ?? "" };
    case "escalation":
      return {
        kind: "escalation", ticketId: wire.ticket_id ?? "", displayId: wire.display_id ?? null,
        reason: wire.reason ?? null, assignedTeam: wire.assigned_team ?? null,
      };
    default:
      // A response shape this app doesn't know about — surfaced as a genuine
      // error rather than silently rendered as blank text (never fabricate).
      throw new Error(`Unknown message turn type from backend: '${wire.type}'`);
  }
}

export async function startConversation(): Promise<Conversation> {
  const response = await authedFetch(BASE, { method: "POST", idempotencyKey: newIdempotencyKey() });
  const body = (await response.json()) as StartConversationResponseWire;
  return { conversationId: body.conversation_id, startedAt: body.started_at };
}

export async function sendMessage(conversationId: string, text: string, attachmentRefs: string[]): Promise<MessageTurn> {
  const response = await authedFetch(`${BASE}/${conversationId}/messages`, {
    method: "POST",
    idempotencyKey: newIdempotencyKey(),
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ text, attachment_refs: attachmentRefs }),
  });
  return toMessageTurn((await response.json()) as MessageTurnResponseWire);
}

export async function confirmAction(conversationId: string, actionId: string): Promise<ActionOutcome> {
  const response = await authedFetch(`${BASE}/${conversationId}/actions/${actionId}/confirm`, {
    method: "POST", idempotencyKey: newIdempotencyKey(),
  });
  return ((await response.json()) as ActionOutcomeResponseWire).outcome as ActionOutcome;
}

export async function declineAction(conversationId: string, actionId: string): Promise<ActionOutcome> {
  const response = await authedFetch(`${BASE}/${conversationId}/actions/${actionId}/decline`, {
    method: "POST", idempotencyKey: newIdempotencyKey(),
  });
  return ((await response.json()) as ActionOutcomeResponseWire).outcome as ActionOutcome;
}

export async function findConversation(conversationId: string): Promise<ConversationDetail> {
  const response = await authedFetch(`${BASE}/${conversationId}`, { method: "GET" });
  const body = (await response.json()) as ConversationDetailResponseWire;
  return { conversationId: body.conversation_id, state: body.state, startedAt: body.started_at, updatedAt: body.updated_at };
}

/** Returns null on a real 404 (no prior conversation for this employee) — a genuine "nothing to resume", not an error. */
export async function findMostRecentConversation(): Promise<ConversationDetail | null> {
  try {
    const response = await authedFetch(`${BASE}/most-recent`, { method: "GET" });
    const body = (await response.json()) as ConversationDetailResponseWire;
    return { conversationId: body.conversation_id, state: body.state, startedAt: body.started_at, updatedAt: body.updated_at };
  } catch (error) {
    if (error && typeof error === "object" && "status" in error && (error as { status: number }).status === 404) {
      return null;
    }
    throw error;
  }
}
