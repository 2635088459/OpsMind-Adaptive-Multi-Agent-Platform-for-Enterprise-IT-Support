import { useState } from "react";
import { useSupportTicket } from "@/features/ticket/useSupportTicket";
import { computeTicketProgress, type ProgressStepKey } from "@/features/ticket/ticketProgress";
import { TicketProgressTrail } from "@/features/ticket/TicketProgressTrail";
import { TriageForm } from "@/features/triage/TriageForm";
import { AssignmentForm } from "@/features/assignment/AssignmentForm";
import { StatusTransitionControl } from "@/features/statusTransition/StatusTransitionControl";
import { AiLogPanel } from "@/features/ailog/AiLogPanel";
import { TicketApprovals } from "@/features/approval/TicketApprovals";
import { TicketTraceLink } from "@/features/trace/TicketTraceLink";
import { agentLabel } from "@/features/catalog/catalog";
import { computeSlaDisplay, formatRemaining } from "@/features/queue/slaDisplay";
import type { SupportTicketDetail } from "@/features/ticket/api";

const STEP_BLURB: Record<ProgressStepKey, string> = {
  triage: "Classify the ticket and route it to a queue.",
  assign: "Give the ticket an owner, or hand it on.",
  start: "Move this ticket into active work.",
  resolve: "Advance the ticket, send it for approval, or resolve it.",
};

const STEP_LABEL: Record<ProgressStepKey, string> = {
  triage: "Triage this ticket",
  assign: "Give this ticket an owner",
  start: "Move it forward",
  resolve: "Move it forward",
};

/**
 * The single action panel below the trail — driven by whichever step is
 * active, not a fixed grid of all 3 at once. "start" and "resolve" share
 * `StatusTransitionControl` (it already offers both Start work/Send for
 * approval AND Resolve together — SPEC-SC-012's own real endpoint split,
 * not a client-invented 4th operation).
 */
function ActiveStepPanel({ ticket, step }: { ticket: SupportTicketDetail; step: ProgressStepKey }) {
  if (step === "triage") return <TriageForm ticketId={ticket.ticketId} initialVersion={ticket.version} defaultPriority={ticket.priority} />;
  if (step === "assign") {
    return (
      <AssignmentForm
        ticketId={ticket.ticketId}
        initialVersion={ticket.version}
        initiallyAssigned={ticket.assignment.agentId !== null}
        currentAgentId={ticket.assignment.agentId}
        currentTeamId={ticket.assignment.teamId}
      />
    );
  }
  return <StatusTransitionControl ticketId={ticket.ticketId} initialVersion={ticket.version} />;
}

/**
 * The trail + its one open panel, as a unit keyed by `ticket.version` at the
 * call site below — the SAME mechanism `useTriageTicket`/`useAssignTicket`/
 * `useStatusTransition` already use to invalidate the shared ticket query on
 * their own success (see those hooks' own docs). Remounting this on every
 * real version change resets `activeStep` back to whatever the backend now
 * considers current — a completed action auto-advances the trail to the
 * next real step, and an operator's own manual override (clicking a
 * different node) only lasts for as long as the ticket doesn't itself move.
 */
function TicketActionArea({ ticket }: { ticket: SupportTicketDetail }) {
  const progress = computeTicketProgress(ticket.status, ticket.assignment.agentId);
  const [activeStep, setActiveStep] = useState<ProgressStepKey>(progress.current ?? "resolve");

  if (progress.closed) {
    return (
      <div className="rounded-xl border border-border bg-surface p-5 text-sm text-ink-muted" data-testid="ticket-closed-summary">
        This ticket reached <span className="font-semibold text-ink">{ticket.status}</span> — triage, assignment, and status are
        all settled. Re-open it from a status transition if it needs more work.
      </div>
    );
  }

  return (
    <div>
      <TicketProgressTrail steps={progress.steps} activeStep={activeStep} onSelectStep={setActiveStep} />
      <div className="max-w-[560px] rounded-xl border border-border bg-surface p-6" data-testid="ticket-action-panel">
        <h2 className="font-serif text-lg text-ink">{STEP_LABEL[activeStep]}</h2>
        <p className="mt-0.5 mb-5 text-sm text-ink-muted">{STEP_BLURB[activeStep]}</p>
        <ActiveStepPanel ticket={ticket} step={activeStep} />
      </div>
    </div>
  );
}

/**
 * The detail half of the queue-first split view (Concept C, picked
 * 2026-09-11). Takes `ticketId` as a prop rather than reading `useParams`
 * itself — `SupportDeskPage` owns the route and passes it down, so this
 * same pane can eventually be reused anywhere a ticket needs to render
 * without owning its own route.
 */
export function TicketDetailPane({ ticketId }: { ticketId: string }) {
  const { data: ticket, isLoading, isError, refetch } = useSupportTicket(ticketId);

  if (isLoading) {
    return (
      <div className="p-8 text-sm text-ink-muted" data-testid="ticket-loading">
        Loading ticket…
      </div>
    );
  }

  if (isError || !ticket) {
    return (
      <div className="m-8 rounded-xl border border-danger/30 bg-danger-soft p-6 text-sm text-ink" data-testid="ticket-error">
        <p>Could not load this ticket.</p>
        <button type="button" onClick={() => refetch()} className="mt-2 rounded-md border border-border bg-surface px-3 py-1.5 text-sm font-medium hover:bg-surface-muted">
          Retry
        </button>
      </div>
    );
  }

  const sla = computeSlaDisplay(ticket.sla.state, ticket.sla.resolutionDueAt);
  const slaText =
    sla.remainingMs !== null
      ? `${sla.state === "overdue" ? "Overdue by " : ""}${formatRemaining(sla.remainingMs)}`
      : sla.state === "inactive"
        ? "Not tracked"
        : "—";

  return (
    <div className="px-8 py-7">
      <div className="flex flex-wrap items-start justify-between gap-5">
        <div>
          <p className="font-mono text-sm text-ink-muted">
            {ticket.displayId} &nbsp;·&nbsp; {ticket.priority} &nbsp;·&nbsp; {ticket.assignment.teamId ?? "no team"}
            {ticket.assignment.agentId ? <> &nbsp;·&nbsp; {agentLabel(ticket.assignment.agentId)}</> : null}
            &nbsp;·&nbsp; <TicketTraceLink ticketId={ticket.ticketId} />
          </p>
          <h1 className="mt-1 font-serif text-2xl italic text-ink" data-testid="ticket-title">
            {ticket.title}
          </h1>
        </div>
        <div className="text-right">
          <div className="text-[.68rem] uppercase tracking-wide text-faint">Resolution due</div>
          <div className="font-serif text-xl text-ink">{slaText}</div>
        </div>
      </div>
      <p className="mt-2.5 max-w-[58ch] text-sm leading-relaxed text-ink-muted">{ticket.description}</p>

      <div className="mt-7">
        <TicketActionArea key={ticket.version} ticket={ticket} />
      </div>

      <div className="mt-8 grid gap-5 lg:grid-cols-2">
        <section className="rounded-xl border border-border bg-surface p-5">
          <h2 className="text-sm font-semibold text-ink">AI activity</h2>
          <p className="mt-0.5 text-xs text-ink-muted">What agent-runtime did on this ticket.</p>
          <div className="mt-4">
            <AiLogPanel ticketId={ticket.ticketId} toolRequestId={null} />
          </div>
        </section>
        <section className="rounded-xl border border-border bg-surface p-5">
          <h2 className="text-sm font-semibold text-ink">Approval</h2>
          <p className="mt-0.5 text-xs text-ink-muted">Governance approval requests linked to this ticket.</p>
          <div className="mt-4">
            <TicketApprovals ticketId={ticket.ticketId} />
          </div>
        </section>
      </div>
    </div>
  );
}
