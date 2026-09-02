import { describe, it, expect, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { renderWithProviders } from "@/test/renderWithProviders";
import { useAuthStore } from "@/store/authStore";
import { useConversationStore } from "@/features/conversation/conversationStore";
import { useAttachmentStore } from "@/features/attachment/attachmentStore";
import { useTurnStore } from "@/features/conversation/turnStore";
import { AGENT_RUNTIME_BASE_URL } from "@/lib/env";
import { ConversationView } from "@/features/conversation/ConversationView";
import { ProposedActionCard } from "@/features/conversation/ProposedActionCard";
import { EscalationNotice } from "@/features/conversation/EscalationNotice";
import { AttachmentPicker } from "@/features/attachment/AttachmentPicker";

const BASE = `${AGENT_RUNTIME_BASE_URL}/api/v1/conversations`;

/**
 * SPEC-EP-022: the highest-priority security spec in this domain — agent
 * output is inherently less trusted than backend-validated data. React's
 * own default string-child escaping is this app's real defense (confirmed
 * by grepping the whole app for `dangerouslySetInnerHTML`/`innerHTML`:
 * zero occurrences, nothing to audit away) — these tests PROVE that
 * defense holds across every real content-rendering surface with a real
 * adversarial payload, rather than asserting it by code inspection alone.
 */
const XSS_PAYLOAD = '<img src=x onerror="window.__xss_fired = true">';
const SCRIPT_PAYLOAD = "<script>window.__xss_fired = true</script>";

function resetXssFlag() {
  (window as unknown as { __xss_fired?: boolean }).__xss_fired = undefined;
}

function assertNoScriptExecuted() {
  expect((window as unknown as { __xss_fired?: boolean }).__xss_fired).toBeUndefined();
}

describe("XSS audit — every real content-rendering surface", () => {
  beforeEach(() => {
    resetXssFlag();
    useAuthStore.setState({ status: "authenticated", accessToken: "fake-token", error: null });
    useConversationStore.getState().reset();
    useAttachmentStore.getState().reset();
    useTurnStore.setState({ state: "IDLE" });
  });

  it("SPEC-EP-005: an adversarial agent 'text' response renders as inert text, never executes", async () => {
    useConversationStore.setState({ conversationId: "conv-1" });
    server.use(http.post(`${BASE}/conv-1/messages`, () => HttpResponse.json({ type: "text", text: XSS_PAYLOAD })));
    const user = userEvent.setup();
    renderWithProviders(<ConversationView />);

    await user.type(screen.getByPlaceholderText(/describe your issue/i), "hello");
    await user.click(screen.getByRole("button", { name: /^send$/i }));

    expect(await screen.findByText(XSS_PAYLOAD)).toBeInTheDocument();
    assertNoScriptExecuted();
  });

  it("SPEC-EP-005: an adversarial employee-typed message also renders as inert text", async () => {
    useConversationStore.setState({ conversationId: "conv-1" });
    server.use(http.post(`${BASE}/conv-1/messages`, () => HttpResponse.json({ type: "text", text: "ok" })));
    const user = userEvent.setup();
    renderWithProviders(<ConversationView />);

    await user.type(screen.getByPlaceholderText(/describe your issue/i), SCRIPT_PAYLOAD);
    await user.click(screen.getByRole("button", { name: /^send$/i }));

    expect(await screen.findByText(SCRIPT_PAYLOAD)).toBeInTheDocument();
    assertNoScriptExecuted();
  });

  it("SPEC-EP-007: an adversarial proposed-action summary renders as inert text", () => {
    renderWithProviders(
      <ProposedActionCard action={{ actionId: "a1", summary: XSS_PAYLOAD, riskLevel: "LOW" }} onConfirm={() => {}} onDecline={() => {}} disabled={false} />,
    );

    expect(screen.getByTestId("proposed-action-summary")).toHaveTextContent(XSS_PAYLOAD);
    assertNoScriptExecuted();
  });

  it("SPEC-EP-012: adversarial escalation reason/assignedTeam text render as inert text", () => {
    renderWithProviders(<EscalationNotice escalation={{ ticketId: "t1", displayId: null, reason: SCRIPT_PAYLOAD, assignedTeam: XSS_PAYLOAD }} />);

    expect(screen.getByTestId("escalation-notice")).toHaveTextContent(SCRIPT_PAYLOAD);
    assertNoScriptExecuted();
  });

  it("SPEC-EP-010: an adversarial attachment filename renders as an inert text node", () => {
    useAttachmentStore.getState().stage("attachment-1", XSS_PAYLOAD);
    renderWithProviders(<AttachmentPicker />);

    expect(screen.getByTestId("staged-attachment")).toHaveTextContent(XSS_PAYLOAD);
    assertNoScriptExecuted();
  });
});
