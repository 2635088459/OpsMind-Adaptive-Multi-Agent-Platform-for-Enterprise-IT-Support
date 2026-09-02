import { describe, it, expect, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { useAuthStore } from "@/store/authStore";
import { useConversationStore } from "@/features/conversation/conversationStore";
import { useTurnStore } from "@/features/conversation/turnStore";
import { AGENT_RUNTIME_BASE_URL } from "@/lib/env";
import { saveDraft } from "@/features/session/draftPreservation";
import { useResumeConversation } from "@/features/conversation/useResumeConversation";

const MOST_RECENT = `${AGENT_RUNTIME_BASE_URL}/api/v1/conversations/most-recent`;

describe("useResumeConversation", () => {
  beforeEach(() => {
    useConversationStore.getState().reset();
    useTurnStore.setState({ state: "IDLE" });
    useAuthStore.setState({ status: "authenticated", accessToken: "fake-token", lastKnownSubject: "employee-1", error: null });
    localStorage.clear();
  });

  it("SPEC-EP-015: resumes the real most-recent conversation and seeds IDLE", async () => {
    server.use(http.get(MOST_RECENT, () => HttpResponse.json({
      conversation_id: "conv-1", state: "RUNNING", started_at: "2026-01-01T00:00:00Z", updated_at: "2026-01-01T00:05:00Z",
    })));

    renderHook(() => useResumeConversation());

    await waitFor(() => expect(useConversationStore.getState().conversationId).toBe("conv-1"));
    expect(useTurnStore.getState().state).toBe("IDLE");
  });

  it("does nothing when there is genuinely no prior conversation (real 404)", async () => {
    server.use(http.get(MOST_RECENT, () => HttpResponse.json(
      { error: { code: "CONVERSATION_NOT_FOUND", message: "not found" } }, { status: 404 },
    )));

    renderHook(() => useResumeConversation());

    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(useConversationStore.getState().conversationId).toBeNull();
  });

  it("SPEC-EP-003: restores a preserved draft once the conversation it belongs to is re-established", async () => {
    saveDraft("employee-1", "conv-1", "my vpn keeps disconnecting");
    server.use(http.get(MOST_RECENT, () => HttpResponse.json({
      conversation_id: "conv-1", state: "RUNNING", started_at: "2026-01-01T00:00:00Z", updated_at: "2026-01-01T00:05:00Z",
    })));

    renderHook(() => useResumeConversation());

    await waitFor(() => expect(useConversationStore.getState().draftText).toBe("my vpn keeps disconnecting"));
  });

  it("never fires again once a conversationId is already set (does not clobber an active session)", async () => {
    useConversationStore.setState({ conversationId: "conv-already-active" });
    let callCount = 0;
    server.use(http.get(MOST_RECENT, () => {
      callCount += 1;
      return HttpResponse.json({ conversation_id: "conv-2", state: "RUNNING", started_at: "x", updated_at: "y" });
    }));

    renderHook(() => useResumeConversation());
    await new Promise((resolve) => setTimeout(resolve, 10));

    expect(callCount).toBe(0);
    expect(useConversationStore.getState().conversationId).toBe("conv-already-active");
  });
});
