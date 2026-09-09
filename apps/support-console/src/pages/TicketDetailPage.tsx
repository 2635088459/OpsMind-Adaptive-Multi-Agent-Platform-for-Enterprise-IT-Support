import { Link, useParams } from "react-router";
import { useSupportTicket } from "@/features/ticket/useSupportTicket";
import { TriageForm } from "@/features/triage/TriageForm";
import { AssignmentForm } from "@/features/assignment/AssignmentForm";
import { StatusTransitionControl } from "@/features/statusTransition/StatusTransitionControl";
import { AiLogPanel } from "@/features/ailog/AiLogPanel";
import { TicketApprovals } from "@/features/approval/TicketApprovals";
import { TicketTraceLink } from "@/features/trace/TicketTraceLink";
import { agentLabel } from "@/features/catalog/catalog";

/**
 * A real route for one ticket, backed by `GET /api/v1/tickets/{id}` — which
 * renders the Support view (with assignment) off the caller's JWT actor_type.
 * The optimistic-concurrency `version` from that response feeds the
 * triage/assign/status panels.
 */
function Panel({ title, blurb, children, wide = false }: { title: string; blurb: string; children: React.ReactNode; wide?: boolean }) {
  return (
    <section className={`rounded-xl border border-border bg-surface p-5 ${wide ? "lg:col-span-2" : ""}`}>
      <h2 className="text-base font-semibold text-ink">{title}</h2>
      <p className="mt-0.5 text-sm text-ink-muted">{blurb}</p>
      <div className="mt-4">{children}</div>
    </section>
  );
}

export function TicketDetailPage() {
  const { ticketId = "" } = useParams();
  const { data: ticket, isLoading, isError, refetch } = useSupportTicket(ticketId);

  return (
    <div>
      <Link to="/" className="text-sm text-ink-muted hover:underline">
        ← Back to queue
      </Link>

      {isLoading ? (
        <div className="mt-4 rounded-xl border border-border bg-surface p-6 text-sm text-ink-muted" data-testid="ticket-loading">
          Loading ticket…
        </div>
      ) : isError || !ticket ? (
        <div className="mt-4 rounded-xl border border-danger/30 bg-danger/5 p-6 text-sm text-ink" data-testid="ticket-error">
          <p>Could not load this ticket.</p>
          <button type="button" onClick={() => refetch()} className="mt-2 rounded-md border border-border bg-surface px-3 py-1.5 text-sm font-medium hover:bg-surface-muted">
            Retry
          </button>
        </div>
      ) : (
        <>
          <div className="mt-3 flex flex-wrap items-center gap-3">
            <h1 className="font-mono text-2xl font-semibold text-ink">{ticket.displayId}</h1>
            <span className="rounded-full border border-border bg-surface-muted px-2.5 py-0.5 text-xs font-medium text-ink-muted">
              {ticket.status}
            </span>
            <span className="rounded-full border border-border bg-surface-muted px-2.5 py-0.5 text-xs font-medium text-ink-muted">
              {ticket.priority}
            </span>
            <span className="rounded-full border border-border bg-surface-muted px-2.5 py-0.5 text-xs font-medium text-ink-muted">
              {ticket.assignment.teamId ?? "no team"}
            </span>
            <span
              className="rounded-full border border-border bg-surface-muted px-2.5 py-0.5 text-xs font-medium text-ink-muted"
              title={ticket.assignment.agentId ?? undefined}
            >
              {ticket.assignment.agentId ? agentLabel(ticket.assignment.agentId) : "unassigned"}
            </span>
            <TicketTraceLink ticketId={ticket.ticketId} />
          </div>
          <p className="mt-2 text-lg text-ink">{ticket.title}</p>
          <p className="mt-1 whitespace-pre-wrap text-sm text-ink-muted">{ticket.description}</p>

          <div className="mt-6 grid gap-5 lg:grid-cols-2">
            <Panel title="Triage" blurb="Classify the ticket and route it to a queue.">
              <TriageForm ticketId={ticket.ticketId} initialVersion={ticket.version} defaultPriority={ticket.priority} />
            </Panel>
            <Panel title="Assignment" blurb="Give the ticket an owner, or hand it on.">
              <AssignmentForm
                ticketId={ticket.ticketId}
                initialVersion={ticket.version}
                initiallyAssigned={ticket.assignment.agentId !== null || ticket.assignment.teamId !== null}
                currentAgentId={ticket.assignment.agentId}
                currentTeamId={ticket.assignment.teamId}
              />
            </Panel>
            <Panel title="Status" blurb="Advance the ticket, send it for approval, or resolve it.">
              <StatusTransitionControl ticketId={ticket.ticketId} initialVersion={ticket.version} />
            </Panel>
            <Panel title="AI activity" blurb="What agent-runtime did on this ticket.">
              <AiLogPanel ticketId={ticket.ticketId} toolRequestId={null} />
            </Panel>
            <Panel title="Approval" blurb="Governance approval requests linked to this ticket." wide>
              <TicketApprovals ticketId={ticket.ticketId} />
            </Panel>
          </div>
        </>
      )}
    </div>
  );
}
