package dev.opsmind.ticketworkflow.ticket.api.support;

import dev.opsmind.ticketworkflow.ticket.application.event.RequesterPseudonymizer;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportTicketProjection;
import dev.opsmind.ticketworkflow.ticket.domain.value.RequesterId;
import org.springframework.stereotype.Component;

@Component
public class SupportTicketQueryApiMapper {

    private final RequesterPseudonymizer requesterPseudonymizer;

    public SupportTicketQueryApiMapper(RequesterPseudonymizer requesterPseudonymizer) {
        this.requesterPseudonymizer = requesterPseudonymizer;
    }

    public SupportTicketDetailResponse toResponse(SupportTicketProjection projection) {
        return new SupportTicketDetailResponse(
            projection.ticketId(),
            projection.displayId(),
            projection.title(),
            projection.description(),
            projection.applicationCode(),
            projection.source(),
            projection.status(),
            projection.priority(),
            requesterPseudonymizer.pseudonymize(RequesterId.of(projection.requesterId())),
            new SupportTicketDetailResponse.Assignment(
                projection.currentTeamId(),
                projection.currentSupportUserId(),
                projection.applicationCode()
            ),
            new SupportTicketDetailResponse.ResolutionCycle(
                projection.resolutionCycleNumber() == null ? 0 : projection.resolutionCycleNumber(),
                projection.resolutionCycleStatus()
            ),
            new SupportTicketDetailResponse.Sla(
                mapSlaState(projection.slaState()),
                projection.slaPolicyId(),
                projection.slaResponseDueAt(),
                projection.slaResolutionDueAt()
            ),
            projection.createdAt(),
            projection.updatedAt(),
            projection.version()
        );
    }

    static String mapSlaState(String dbSlaStatus) {
        return "MET".equals(dbSlaStatus) ? "COMPLETED" : dbSlaStatus;
    }
}
