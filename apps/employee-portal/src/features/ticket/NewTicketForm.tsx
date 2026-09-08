import { useState } from "react";
import { useCreateTicket } from "@/features/ticket/useCreateTicket";
import { TicketStatusPanel } from "@/features/ticket/TicketStatusPanel";
import { usePortalViewStore } from "@/store/portalViewStore";
import type { TicketApplicationCode } from "@/features/ticket/types";

// ticket-workflow-service's real ApplicationCode enum, paired with plain-
// language labels for the employee. The values are passed to the backend
// verbatim; only the labels are ours.
const CATEGORIES: { value: TicketApplicationCode; label: string }[] = [
  { value: "VPN", label: "VPN / remote access" },
  { value: "EMAIL", label: "Email" },
  { value: "HOUSING_PORTAL", label: "Housing Portal" },
  { value: "OTHER", label: "Something else" },
];

// Real backend validation on CreateTicketRequest: @NotBlank @Size(max=…) on
// both fields. Enforced here too so an over-long value is caught before it
// becomes a guaranteed 400.
const TITLE_MAX = 200;
const DESCRIPTION_MAX = 10_000;

/**
 * The employee's deliberate "skip the assistant, file a ticket for a human"
 * path — a real form over `POST /api/v1/tickets` (see
 * features/ticket/api.ts#createTicket). A ticket created here enters
 * ticket-workflow-service's normal queue for a support agent; the assistant
 * never runs on it. On success the employee gets the real display id and can
 * open the same live status panel the escalation flow uses.
 */
export function NewTicketForm() {
  const showConversation = usePortalViewStore((state) => state.showConversation);
  const createTicket = useCreateTicket();

  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [category, setCategory] = useState<TicketApplicationCode>("OTHER");
  const [viewingStatus, setViewingStatus] = useState(false);

  const trimmedTitle = title.trim();
  const trimmedDescription = description.trim();
  const canSubmit =
    trimmedTitle.length > 0 &&
    trimmedTitle.length <= TITLE_MAX &&
    trimmedDescription.length > 0 &&
    trimmedDescription.length <= DESCRIPTION_MAX &&
    !createTicket.isPending;

  const created = createTicket.data;

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    if (!canSubmit) return;
    createTicket.mutate({ title: trimmedTitle, description: trimmedDescription, applicationCode: category });
  };

  return (
    <div className="mx-auto flex min-h-screen max-w-2xl flex-col px-5 py-7 sm:px-6">
      <header className="flex items-center justify-between gap-3 pb-5">
        <div className="flex items-center gap-2.5">
          <div className="flex size-8 shrink-0 items-center justify-center rounded-[9px] bg-brand-600 text-sm font-extrabold tracking-tight text-white">
            OM
          </div>
          <span className="text-base font-bold tracking-tight text-ink">OpsMind</span>
          <span className="border-l border-border pl-2 text-xs text-faint">IT Support</span>
        </div>
        <button
          type="button"
          onClick={showConversation}
          className="rounded-md border border-border bg-surface px-3 py-1.5 text-sm font-medium text-ink-muted hover:bg-surface-muted"
        >
          ← Back to chat
        </button>
      </header>

      <div className="rounded-2xl border border-border bg-surface p-5 sm:p-6">
        {created ? (
          <div data-testid="new-ticket-success">
            <h1 className="text-base font-semibold text-ink">Ticket submitted</h1>
            <p className="mt-2 text-sm text-ink-muted">
              Your ticket <span className="font-mono font-semibold text-ink">{created.displayId}</span> has been created. A
              human support agent will follow up — you don&apos;t need to do anything else.
            </p>
            <div className="mt-4 flex flex-wrap gap-2">
              <button
                type="button"
                onClick={() => setViewingStatus((v) => !v)}
                className="rounded-md bg-brand-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-brand-700"
              >
                {viewingStatus ? "Hide status" : "View ticket status"}
              </button>
              <button
                type="button"
                onClick={showConversation}
                className="rounded-md border border-border px-3 py-1.5 text-sm font-medium hover:bg-surface-muted"
              >
                Back to chat
              </button>
            </div>
            {viewingStatus ? (
              <div className="mt-4">
                <TicketStatusPanel ticketId={created.ticketId} />
              </div>
            ) : null}
          </div>
        ) : (
          <form onSubmit={submit} noValidate>
            <h1 className="text-base font-semibold text-ink">Submit a ticket</h1>
            <p className="mt-1 text-sm text-ink-muted">
              Prefer not to chat? Describe your issue and it goes straight to a human support agent.
            </p>

            <label htmlFor="new-ticket-title" className="mt-5 block text-sm font-medium text-ink">
              Summary
            </label>
            <input
              id="new-ticket-title"
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              maxLength={TITLE_MAX}
              placeholder="e.g. VPN disconnects every few minutes"
              className="mt-1 w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-ink placeholder:text-faint"
            />

            <label htmlFor="new-ticket-category" className="mt-4 block text-sm font-medium text-ink">
              Category
            </label>
            <select
              id="new-ticket-category"
              value={category}
              onChange={(event) => setCategory(event.target.value as TicketApplicationCode)}
              className="mt-1 w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-ink"
            >
              {CATEGORIES.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>

            <label htmlFor="new-ticket-description" className="mt-4 block text-sm font-medium text-ink">
              Details
            </label>
            <textarea
              id="new-ticket-description"
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              maxLength={DESCRIPTION_MAX}
              rows={6}
              placeholder="What's happening, what you've already tried, any error messages…"
              className="mt-1 w-full resize-none rounded-md border border-border bg-surface px-3 py-2 text-sm text-ink placeholder:text-faint"
            />

            {createTicket.isError ? (
              <p className="mt-3 rounded-md border border-danger/30 bg-danger/5 px-3 py-2 text-sm text-danger" data-testid="new-ticket-error">
                Could not submit your ticket: {createTicket.error.message}
              </p>
            ) : null}

            <div className="mt-5 flex gap-2">
              <button
                type="submit"
                disabled={!canSubmit}
                className="rounded-md bg-brand-600 px-4 py-2 text-sm font-medium text-white hover:bg-brand-700 disabled:cursor-not-allowed disabled:opacity-60"
              >
                {createTicket.isPending ? "Submitting…" : "Submit ticket"}
              </button>
              <button
                type="button"
                onClick={showConversation}
                className="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-surface-muted"
              >
                Cancel
              </button>
            </div>
          </form>
        )}
      </div>
    </div>
  );
}
