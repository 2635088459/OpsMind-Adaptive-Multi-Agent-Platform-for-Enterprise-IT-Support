import { useState } from "react";
import { useSendMessage } from "@/features/conversation/useSendMessage";
import { useTurnStore } from "@/features/conversation/turnStore";
import { useConversationStore } from "@/features/conversation/conversationStore";
import { useAttachmentStore } from "@/features/attachment/attachmentStore";
import { AttachmentPicker } from "@/features/attachment/AttachmentPicker";
import { createTicketManually } from "@/features/ticket/api";
import { useOnlineStatus } from "@/features/session/useOnlineStatus";

/**
 * SPEC-EP-005/010: the composer itself, plus attachments (BI-EP-002: send is
 * gated on every staged attachment being `ready`, never a `validating`/
 * `uploading`/`failed` one) and SPEC-EP-018/019's own resilience UI.
 * Disabled outside `IDLE` (BI-EP-002/003) or while offline (SPEC-EP-019).
 */
export function MessageComposer() {
  const text = useConversationStore((state) => state.draftText);
  const setText = useConversationStore((state) => state.setDraftText);
  const lastFailedText = useConversationStore((state) => state.lastFailedText);
  const turnState = useTurnStore((state) => state.state);
  const sendMessage = useSendMessage();
  const attachments = useAttachmentStore((state) => state.attachments);
  const resetAttachments = useAttachmentStore((state) => state.reset);
  const isOnline = useOnlineStatus();
  const [manualTicketResult, setManualTicketResult] = useState<{ displayId: string } | null>(null);
  const [creatingManualTicket, setCreatingManualTicket] = useState(false);

  const attachmentsReady = attachments.every((a) => a.status === "ready");
  const canCompose = turnState === "IDLE" && isOnline;
  // Real bug found live: gating the retry button on `canCompose` (IDLE-only)
  // made it structurally impossible to ever fire — retry is only ever
  // clickable while turnState IS AGENT_UNAVAILABLE, never IDLE, so that
  // guard silently swallowed every retry click before the mutation was even
  // called.
  const canRetry = turnState === "AGENT_UNAVAILABLE" && isOnline;

  const submitNewMessage = () => {
    const trimmed = text.trim();
    if (!trimmed || !canCompose || !attachmentsReady) return;
    const attachmentRefs = attachments.map((a) => a.ref).filter((ref): ref is string => ref !== null);
    sendMessage.mutate({ text: trimmed, attachmentRefs });
    resetAttachments();
  };

  const retry = () => {
    if (!lastFailedText || !canRetry) return;
    sendMessage.mutate({ text: lastFailedText, attachmentRefs: [] });
  };

  if (turnState === "AGENT_UNAVAILABLE") {
    return (
      <div className="rounded-xl border border-danger/30 bg-danger/5 p-4 text-sm text-ink" data-testid="agent-unavailable-banner">
        <p>The agent is temporarily unavailable.</p>
        {lastFailedText ? <p className="mt-2 rounded-md bg-surface px-3 py-2 text-ink-muted">{lastFailedText}</p> : null}
        <div className="mt-2 flex flex-wrap gap-2">
          <button
            type="button"
            onClick={retry}
            className="rounded-md border border-border bg-surface px-3 py-1.5 text-sm font-medium hover:bg-surface-muted"
          >
            Retry
          </button>
          <button
            type="button"
            disabled={creatingManualTicket}
            onClick={async () => {
              setCreatingManualTicket(true);
              try {
                const result = await createTicketManually(
                  (lastFailedText ?? "Support request from the employee portal").slice(0, 200),
                  lastFailedText ?? "Submitted manually after the assistant became unavailable.",
                );
                setManualTicketResult(result);
              } finally {
                setCreatingManualTicket(false);
              }
            }}
            className="rounded-md bg-brand-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-brand-700 disabled:opacity-60"
          >
            Create a ticket manually instead
          </button>
        </div>
        {manualTicketResult ? (
          <p className="mt-2 text-sm text-ink" data-testid="manual-ticket-created">
            Ticket {manualTicketResult.displayId} created — a human agent will follow up.
          </p>
        ) : null}
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-2">
      {!isOnline ? (
        <div className="rounded-md border border-border bg-surface-muted px-3 py-2 text-sm text-ink-muted" data-testid="offline-banner">
          You&apos;re offline. Your draft is saved — sending will resume once you&apos;re back online.
        </div>
      ) : null}
      <AttachmentPicker />
      <form
        className="flex items-center gap-2.5"
        onSubmit={(event) => {
          event.preventDefault();
          submitNewMessage();
        }}
      >
        <textarea
          value={text}
          onChange={(event) => setText(event.target.value)}
          disabled={!canCompose}
          placeholder="Describe your issue…"
          rows={1}
          className="flex-1 resize-none rounded-xl border border-border bg-surface-muted px-3.5 py-2.5 text-sm text-ink placeholder:text-faint disabled:opacity-60"
        />
        <button
          type="submit"
          disabled={!canCompose || text.trim().length === 0 || !attachmentsReady}
          aria-label="Send"
          className="flex size-[34px] shrink-0 items-center justify-center rounded-[9px] bg-brand-600 text-white hover:bg-brand-700 disabled:cursor-not-allowed disabled:opacity-60"
        >
          <span aria-hidden="true">↑</span>
        </button>
      </form>
    </div>
  );
}
