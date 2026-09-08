import { Link, useParams } from "react-router";
import { useSupportTicket } from "@/features/ticket/useSupportTicket";
import { TriageForm } from "@/features/triage/TriageForm";
import { AssignmentForm } from "@/features/assignment/AssignmentForm";
import { StatusTransitionControl } from "@/features/statusTransition/StatusTransitionControl";
import { AiLogPanel } from "@/features/ailog/AiLogPanel";

/**
 * A real route for one ticket, backed by `GET /api/v1/tickets/{id}` — which
 * renders the Support view (with assignment) off the caller's JWT actor_type.
 * The optimistic-concurrency `version` from that response feeds the
 * triage/assign/status panels.
 */
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
          <div className="mt-3 flex items-baseline gap-3">
            <h1 className="font-mono text-xl font-semibold text-ink">{ticket.displayId}</h1>
            <span className="text-sm text-ink-muted">
              {ticket.status} · {ticket.priority}
              {ticket.assignment.teamId ? ` · ${ticket.assignment.teamId}` : " · unassigned"}
            </span>
          </div>
          <p className="mt-1 text-ink-muted">{ticket.title}</p>
          <p className="mt-1 whitespace-pre-wrap text-sm text-ink-muted">{ticket.description}</p>

          <div className="mt-6 grid gap-6 lg:grid-cols-2">
            <section className="rounded-xl border border-border bg-surface p-4">
              <h2 className="text-sm font-semibold text-ink">Triage</h2>
              <div className="mt-3">
                <TriageForm ticketId={ticket.ticketId} initialVersion={ticket.version} />
              </div>
            </section>
            <section className="rounded-xl border border-border bg-surface p-4">
              <h2 className="text-sm font-semibold text-ink">Assignment</h2>
              <div className="mt-3">
                <AssignmentForm
                  ticketId={ticket.ticketId}
                  initialVersion={ticket.version}
                  initiallyAssigned={ticket.assignment.agentId !== null || ticket.assignment.teamId !== null}
                />
              </div>
            </section>
            <section className="rounded-xl border border-border bg-surface p-4">
              <h2 className="text-sm font-semibold text-ink">Status</h2>
              <div className="mt-3">
                <StatusTransitionControl ticketId={ticket.ticketId} initialVersion={ticket.version} />
              </div>
            </section>
            <section className="rounded-xl border border-border bg-surface p-4">
              <h2 className="text-sm font-semibold text-ink">AI activity</h2>
              <div className="mt-3">
                <AiLogPanel ticketId={ticket.ticketId} toolRequestId={null} />
              </div>
            </section>
          </div>
        </>
      )}
    </div>
  );
}
