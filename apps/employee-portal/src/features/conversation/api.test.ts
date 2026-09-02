import { describe, it, expect, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { useAuthStore } from "@/store/authStore";
import { AGENT_RUNTIME_BASE_URL } from "@/lib/env";
import { ApiError } from "@/lib/apiError";
import {
  confirmAction, declineAction, findConversation, findMostRecentConversation, sendMessage, startConversation,
} from "@/features/conversation/api";

const BASE = `${AGENT_RUNTIME_BASE_URL}/api/v1/conversations`;

/**
 * SPEC-EP-004/005/008/009's own "Tests First" policy: a contract test against
 * an MSW mock, written to the REAL wire shape (snake_case — confirmed by
 * reading agent-runtime-service's schemas.py directly), not the aspirational
 * camelCase this domain's own spec prose describes.
 */
describe("conversation api", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "authenticated", accessToken: "fake-token", error: null });
  });

  it("startConversation maps the real snake_case response and sends a real Idempotency-Key", async () => {
    let receivedIdempotencyKey: string | null = null;
    server.use(
      http.post(BASE, ({ request }) => {
        receivedIdempotencyKey = request.headers.get("Idempotency-Key");
        return HttpResponse.json({ conversation_id: "conv-1", started_at: "2026-01-01T00:00:00Z" }, { status: 201 });
      }),
    );

    const result = await startConversation();

    expect(result).toEqual({ conversationId: "conv-1", startedAt: "2026-01-01T00:00:00Z" });
    expect(receivedIdempotencyKey).toBeTruthy();
  });

  it("sendMessage renders the 'text' response shape", async () => {
    server.use(
      http.post(`${BASE}/conv-1/messages`, () => HttpResponse.json({ type: "text", text: "Try restarting your VPN client." })),
    );

    expect(await sendMessage("conv-1", "my vpn is down", [])).toEqual({ kind: "text", text: "Try restarting your VPN client." });
  });

  it("sendMessage renders the 'proposedAction' response shape", async () => {
    server.use(
      http.post(`${BASE}/conv-1/messages`, () => HttpResponse.json({
        type: "proposedAction", action_id: "action-1", summary: "Reset your VPN client configuration.", risk_level: "LOW",
      })),
    );

    expect(await sendMessage("conv-1", "my vpn is down", [])).toEqual({
      kind: "proposedAction", actionId: "action-1", summary: "Reset your VPN client configuration.", riskLevel: "LOW",
    });
  });

  it("sendMessage renders the 'escalation' response shape", async () => {
    server.use(
      http.post(`${BASE}/conv-1/messages`, () => HttpResponse.json({
        type: "escalation", ticket_id: "ticket-1", display_id: "TCK-100", reason: "needs hardware replacement", assigned_team: "Desktop Support",
      })),
    );

    expect(await sendMessage("conv-1", "my laptop won't turn on", [])).toEqual({
      kind: "escalation", ticketId: "ticket-1", displayId: "TCK-100", reason: "needs hardware replacement", assignedTeam: "Desktop Support",
    });
  });

  it("sendMessage throws on an unrecognized response shape rather than fabricating a rendering", async () => {
    server.use(http.post(`${BASE}/conv-1/messages`, () => HttpResponse.json({ type: "somethingNew" })));

    await expect(sendMessage("conv-1", "hi", [])).rejects.toThrow(/Unknown message turn type/);
  });

  it.each([
    ["done", confirmAction] as const,
    ["still-processing", confirmAction] as const,
    ["awaiting-approval", confirmAction] as const,
    ["declined", declineAction] as const,
  ])("renders the real '%s' outcome honestly, not fabricated as done", async (outcome, call) => {
    server.use(
      http.post(`${BASE}/conv-1/actions/action-1/confirm`, () => HttpResponse.json({ outcome })),
      http.post(`${BASE}/conv-1/actions/action-1/decline`, () => HttpResponse.json({ outcome })),
    );

    expect(await call("conv-1", "action-1")).toBe(outcome);
  });

  it("findConversation maps the resume-query response", async () => {
    server.use(
      http.get(`${BASE}/conv-1`, () => HttpResponse.json({
        conversation_id: "conv-1", state: "RUNNING", started_at: "2026-01-01T00:00:00Z", updated_at: "2026-01-01T00:05:00Z",
      })),
    );

    expect(await findConversation("conv-1")).toEqual({
      conversationId: "conv-1", state: "RUNNING", startedAt: "2026-01-01T00:00:00Z", updatedAt: "2026-01-01T00:05:00Z",
    });
  });

  it("findMostRecentConversation returns null on a real 404 (nothing to resume), not an error", async () => {
    server.use(
      http.get(`${BASE}/most-recent`, () => HttpResponse.json(
        { error: { code: "CONVERSATION_NOT_FOUND", message: "not found" } }, { status: 404 },
      )),
    );

    expect(await findMostRecentConversation()).toBeNull();
  });

  it("a non-404 failure still propagates as a real ApiError", async () => {
    server.use(
      http.get(`${BASE}/most-recent`, () => HttpResponse.json(
        { error: { code: "INTERNAL_ERROR", message: "boom" } }, { status: 500 },
      )),
    );

    await expect(findMostRecentConversation()).rejects.toBeInstanceOf(ApiError);
  });
});
