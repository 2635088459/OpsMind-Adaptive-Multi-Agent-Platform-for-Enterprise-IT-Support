package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import dev.opsmind.ticketworkflow.ticket.application.query.EmployeeTicketProjection;
import org.springframework.stereotype.Component;

@Component
public class PublicTicketQueryApiMapper {

    private static final String TICKETS_PATH = "/api/v1/tickets/";

    public EmployeeTicketDetailResponse toResponse(EmployeeTicketProjection projection) {
        String selfLink = TICKETS_PATH + projection.ticketId();
        return new EmployeeTicketDetailResponse(
            projection.ticketId(),
            projection.displayId(),
            projection.title(),
            projection.description(),
            projection.applicationCode(),
            projection.source(),
            projection.status(),
            projection.priority(),
            projection.createdAt(),
            projection.updatedAt(),
            projection.version(),
            new EmployeeTicketDetailResponse.Sla(
                mapSlaState(projection.slaState()),
                projection.slaResponseDueAt(),
                projection.slaResolutionDueAt()
            ),
            new EmployeeTicketDetailResponse.Links(selfLink, selfLink + "/timeline", selfLink + "/messages")
        );
    }

    static String mapSlaState(String dbSlaStatus) {
        return "MET".equals(dbSlaStatus) ? "COMPLETED" : dbSlaStatus;
    }
}
