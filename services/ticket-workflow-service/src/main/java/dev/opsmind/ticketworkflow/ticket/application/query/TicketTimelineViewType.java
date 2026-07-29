package dev.opsmind.ticketworkflow.ticket.application.query;

/**
 * Server-resolved Ticket Timeline view (SPEC-TW-006 §7). The client cannot
 * request a view directly — {@code ActorContext.actorType} plus the
 * actor's internal-notes scope (for Support) are the only inputs. Auditor
 * resolves to {@link #AUDITOR_POLICY_VIEW} but, mirroring Get Ticket's
 * {@code AUDITOR_VIEW} precedent (SPEC-TW-002), is always denied today: no
 * acceptance scenario in this spec exercises a working Auditor policy, and
 * §21 itself describes the Auditor Timeline as a future dedicated API.
 */
public enum TicketTimelineViewType {
    EMPLOYEE_PUBLIC_VIEW,
    SUPPORT_PUBLIC_VIEW,
    SUPPORT_INTERNAL_VIEW,
    AUDITOR_POLICY_VIEW
}
