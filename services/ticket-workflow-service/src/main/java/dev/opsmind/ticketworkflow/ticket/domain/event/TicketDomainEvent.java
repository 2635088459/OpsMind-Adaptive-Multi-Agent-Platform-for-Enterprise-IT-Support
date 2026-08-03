package dev.opsmind.ticketworkflow.ticket.domain.event;

public sealed interface TicketDomainEvent permits TicketCreated, TicketTriaged, TicketAssigned, TicketReassigned, TicketUnassigned, TicketStatusChanged, TicketResolved, TicketClosed, TicketReopened, TicketUserInputRequested, TicketUserInputResumed, TicketApprovalWaitStarted, TicketApprovalGrantedApplied {
}
