package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import dev.opsmind.ticketworkflow.support.SupportQueueFixtures;
import dev.opsmind.ticketworkflow.ticket.application.cursor.SupportQueueCursorCodec;
import dev.opsmind.ticketworkflow.ticket.application.cursor.SupportQueueCursorSigner;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.policy.TicketViewPolicy;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportQueueQueryPort;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueQuery;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueResult;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueScope;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportTicketSummary;
import dev.opsmind.ticketworkflow.ticket.application.service.QuerySupportQueueApplicationService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SPEC-TW-005 §11: one pagination session uses the cursor's fixed {@code
 * evaluationTime} — acceptance scenario "SLA evaluation time remains
 * fixed across pages": real time advancing between page 1 and page 2 must
 * not change the SLA evaluation instant used for ranking on page 2.
 */
@Tag("unit")
class SupportQueueEvaluationTimeTest {

    private static final Instant PAGE_ONE_TIME = Instant.parse("2026-07-25T19:00:00Z");
    private static final Instant PAGE_TWO_REAL_TIME = PAGE_ONE_TIME.plus(Duration.ofMinutes(30));

    private SupportQueueQueryPort queryPort;
    private ClockPort clock;
    private QuerySupportQueueApplicationService service;
    private SupportQueueScope scope;

    private QuerySupportQueueApplicationService newService() {
        queryPort = mock(SupportQueueQueryPort.class);
        clock = mock(ClockPort.class);
        TicketWorkflowProperties properties = new TicketWorkflowProperties(
            "unit-test-secret", "unit-test-cursor-secret",
            new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(4), Duration.ofDays(7))
        );
        SupportQueueCursorCodec codec = new SupportQueueCursorCodec(new ObjectMapper().findAndRegisterModules(), new SupportQueueCursorSigner(properties));
        scope = SupportQueueFixtures.scope(SupportQueueFixtures.DEFAULT_APPLICATION_CODE);
        return new QuerySupportQueueApplicationService(queryPort, codec, new TicketViewPolicy(), mock(TicketTelemetry.class), clock, properties);
    }

    @Test
    void secondPageShouldEvaluateSlaUsingTheFirstPagesEvaluationTimeNotRealTime() {
        service = newService();
        when(clock.now()).thenReturn(PAGE_ONE_TIME);

        List<SupportTicketSummary> twentySixRows = java.util.stream.IntStream.range(0, 26)
            .mapToObj(i -> SupportQueueFixtures.summary(UUID.randomUUID(), PAGE_ONE_TIME.minusSeconds(i)))
            .toList();
        when(queryPort.queryQueue(any(), any(), eq(PAGE_ONE_TIME), any(), any(), eq(26))).thenReturn(twentySixRows);

        SupportQueueQuery firstPageQuery = new SupportQueueQuery(
            SupportQueueFixtures.supportActor("support-100"), scope, SupportQueueFixtures.noFilters(), 25, null
        );
        SupportQueueResult firstPage = service.query(firstPageQuery);
        assertThat(firstPage.evaluationTime()).isEqualTo(PAGE_ONE_TIME);

        // Real time advances before the second page request.
        when(clock.now()).thenReturn(PAGE_TWO_REAL_TIME);
        when(queryPort.queryQueue(any(), any(), eq(PAGE_ONE_TIME), any(), any(), anyInt())).thenReturn(List.of());

        SupportQueueQuery secondPageQuery = new SupportQueueQuery(
            SupportQueueFixtures.supportActor("support-100"), scope, SupportQueueFixtures.noFilters(), 25, firstPage.nextCursor()
        );
        SupportQueueResult secondPage = service.query(secondPageQuery);

        assertThat(secondPage.evaluationTime()).isEqualTo(PAGE_ONE_TIME);
    }

    @Test
    void firstPageWithNoCursorShouldEvaluateSlaUsingCurrentTime() {
        service = newService();
        when(clock.now()).thenReturn(PAGE_ONE_TIME);
        when(queryPort.queryQueue(any(), any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        SupportQueueQuery query = new SupportQueueQuery(
            SupportQueueFixtures.supportActor("support-100"), scope, SupportQueueFixtures.noFilters(), 25, null
        );
        SupportQueueResult result = service.query(query);

        assertThat(result.evaluationTime()).isEqualTo(PAGE_ONE_TIME);
    }
}
