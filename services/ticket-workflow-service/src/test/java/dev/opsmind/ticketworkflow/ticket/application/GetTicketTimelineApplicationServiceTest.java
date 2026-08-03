package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import dev.opsmind.ticketworkflow.support.TicketTimelineFixtures;
import dev.opsmind.ticketworkflow.ticket.application.cursor.TicketTimelineCursorCodec;
import dev.opsmind.ticketworkflow.ticket.application.cursor.TicketTimelineCursorSigner;
import dev.opsmind.ticketworkflow.ticket.application.exception.InvalidCursorException;
import dev.opsmind.ticketworkflow.ticket.application.exception.SensitiveReadAuditFailureException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.model.SensitiveReadAuditEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.policy.TicketTimelineViewPolicy;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SensitiveReadAuditPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketTimelineQueryPort;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineCursor;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineGuard;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineItem;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineQuery;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineResult;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineSortVersion;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineViewType;
import dev.opsmind.ticketworkflow.ticket.application.service.GetTicketTimelineApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
class GetTicketTimelineApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-25T20:00:00Z");
    private static final UUID TICKET_ID = UUID.randomUUID();

    private TicketTimelineQueryPort queryPort;
    private TicketTimelineCursorCodec cursorCodec;
    private SensitiveReadAuditPort auditPort;
    private TicketTelemetry telemetry;
    private ClockPort clock;
    private GetTicketTimelineApplicationService service;

    @BeforeEach
    void setUp() {
        queryPort = mock(TicketTimelineQueryPort.class);
        auditPort = mock(SensitiveReadAuditPort.class);
        telemetry = mock(TicketTelemetry.class);
        clock = mock(ClockPort.class);
        when(clock.now()).thenReturn(NOW);

        TicketWorkflowProperties properties = new TicketWorkflowProperties(
            "unit-test-secret", "unit-test-cursor-secret",
            new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(4), Duration.ofDays(7))
        );
        cursorCodec = new TicketTimelineCursorCodec(new ObjectMapper().findAndRegisterModules(), new TicketTimelineCursorSigner(properties));

        service = new GetTicketTimelineApplicationService(queryPort, cursorCodec, new TicketTimelineViewPolicy(), auditPort, telemetry, clock);
    }

    private TicketTimelineQuery employeeQuery(String subject, int limit, String cursor) {
        return new TicketTimelineQuery(TicketId.of(TICKET_ID), TicketTimelineFixtures.employeeActor(subject), java.util.Set.of(), limit, cursor);
    }

    @Test
    void shouldReturnEmployeePublicViewForTheOwningEmployee() {
        when(queryPort.loadGuard(any())).thenReturn(Optional.of(TicketTimelineFixtures.guard("employee-123", TicketTimelineFixtures.DEFAULT_APPLICATION_CODE)));
        when(queryPort.queryTimeline(any(), eq(false), any(), isNull(), anyInt())).thenReturn(List.of(
            TicketTimelineFixtures.ticketCreatedItem(TICKET_ID, NOW.minusSeconds(100))
        ));

        TicketTimelineResult result = service.getTimeline(employeeQuery("employee-123", 50, null));

        assertThat(result.viewType()).isEqualTo(TicketTimelineViewType.EMPLOYEE_PUBLIC_VIEW);
        assertThat(result.items()).hasSize(1);
        assertThat(result.displayId()).isEqualTo("INC-2048");
        verify(auditPort, never()).recordSensitiveRead(any());
    }

    @Test
    void shouldRejectNonOwningEmployeeAsNotFound() {
        when(queryPort.loadGuard(any())).thenReturn(Optional.of(TicketTimelineFixtures.guard("employee-999", TicketTimelineFixtures.DEFAULT_APPLICATION_CODE)));

        TicketTimelineQuery query = employeeQuery("employee-123", 50, null);

        assertThatThrownBy(() -> service.getTimeline(query)).isInstanceOf(TicketNotFoundException.class);
        verify(queryPort, never()).queryTimeline(any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), anyInt());
    }

    @Test
    void shouldRejectEmployeeMissingScope() {
        TicketTimelineQuery query = new TicketTimelineQuery(
            TicketId.of(TICKET_ID), TicketTimelineFixtures.employeeActorWithoutScope("employee-123"), java.util.Set.of(), 50, null
        );

        assertThatThrownBy(() -> service.getTimeline(query)).isInstanceOf(TicketAuthorizationException.class);
        verify(queryPort, never()).loadGuard(any());
        verify(telemetry).recordTimelineAuthorizationDenied();
    }

    @Test
    void shouldReturnMissingTicketAsNotFound() {
        when(queryPort.loadGuard(any())).thenReturn(Optional.empty());

        TicketTimelineQuery query = employeeQuery("employee-123", 50, null);

        assertThatThrownBy(() -> service.getTimeline(query)).isInstanceOf(TicketNotFoundException.class);
        verify(telemetry).recordTimelineNotFound();
    }

    @Test
    void shouldReturnSupportPublicViewWithoutInternalScope() {
        TicketTimelineQuery query = new TicketTimelineQuery(
            TicketId.of(TICKET_ID), TicketTimelineFixtures.supportPublicActor("support-100"),
            TicketTimelineFixtures.scope(TicketTimelineFixtures.DEFAULT_APPLICATION_CODE), 50, null
        );
        when(queryPort.loadGuard(any())).thenReturn(Optional.of(TicketTimelineFixtures.guard("employee-123", TicketTimelineFixtures.DEFAULT_APPLICATION_CODE)));
        when(queryPort.queryTimeline(any(), eq(false), any(), isNull(), anyInt())).thenReturn(List.of());

        TicketTimelineResult result = service.getTimeline(query);

        assertThat(result.viewType()).isEqualTo(TicketTimelineViewType.SUPPORT_PUBLIC_VIEW);
        verify(auditPort, never()).recordSensitiveRead(any());
    }

    @Test
    void shouldReturnSupportInternalViewAndRecordRequiredAuditWhenInternalScopeGranted() {
        TicketTimelineQuery query = new TicketTimelineQuery(
            TicketId.of(TICKET_ID), TicketTimelineFixtures.supportInternalActor("support-100"),
            TicketTimelineFixtures.scope(TicketTimelineFixtures.DEFAULT_APPLICATION_CODE), 50, null
        );
        when(queryPort.loadGuard(any())).thenReturn(Optional.of(TicketTimelineFixtures.guard("employee-123", TicketTimelineFixtures.DEFAULT_APPLICATION_CODE)));
        when(queryPort.queryTimeline(any(), eq(true), any(), isNull(), anyInt())).thenReturn(List.of());

        TicketTimelineResult result = service.getTimeline(query);

        assertThat(result.viewType()).isEqualTo(TicketTimelineViewType.SUPPORT_INTERNAL_VIEW);
        var captor = org.mockito.ArgumentCaptor.forClass(SensitiveReadAuditEntry.class);
        verify(auditPort).recordSensitiveRead(captor.capture());
        assertThat(captor.getValue().viewType()).isEqualTo("SUPPORT_INTERNAL_VIEW");
        assertThat(captor.getValue().actorId()).isEqualTo("support-100");
        assertThat(captor.getValue().resourceId()).isEqualTo(TICKET_ID.toString());
    }

    @Test
    void shouldRejectSupportOutsideApplicationScopeAsNotFound() {
        TicketTimelineQuery query = new TicketTimelineQuery(
            TicketId.of(TICKET_ID), TicketTimelineFixtures.supportPublicActor("support-100"),
            TicketTimelineFixtures.scope("VPN"), 50, null
        );
        when(queryPort.loadGuard(any())).thenReturn(Optional.of(TicketTimelineFixtures.guard("employee-123", TicketTimelineFixtures.DEFAULT_APPLICATION_CODE)));

        assertThatThrownBy(() -> service.getTimeline(query)).isInstanceOf(TicketNotFoundException.class);
        verify(queryPort, never()).queryTimeline(any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), anyInt());
    }

    @Test
    void shouldRejectAuditorAsAlwaysDenied() {
        TicketTimelineQuery query = new TicketTimelineQuery(
            TicketId.of(TICKET_ID), TicketTimelineFixtures.auditorActor("auditor-1"), java.util.Set.of(), 50, null
        );

        assertThatThrownBy(() -> service.getTimeline(query)).isInstanceOf(TicketAuthorizationException.class);
        verify(queryPort, never()).loadGuard(any());
    }

    @Test
    void requiredAuditFailureShouldFailTheWholeReadClosed() {
        TicketTimelineQuery query = new TicketTimelineQuery(
            TicketId.of(TICKET_ID), TicketTimelineFixtures.supportInternalActor("support-100"),
            TicketTimelineFixtures.scope(TicketTimelineFixtures.DEFAULT_APPLICATION_CODE), 50, null
        );
        when(queryPort.loadGuard(any())).thenReturn(Optional.of(TicketTimelineFixtures.guard("employee-123", TicketTimelineFixtures.DEFAULT_APPLICATION_CODE)));
        when(queryPort.queryTimeline(any(), eq(true), any(), isNull(), anyInt())).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new RuntimeException("db down")).when(auditPort).recordSensitiveRead(any());

        assertThatThrownBy(() -> service.getTimeline(query)).isInstanceOf(SensitiveReadAuditFailureException.class);
        verify(telemetry).recordTimelineSensitiveReadAuditFailure();
    }

    @Test
    void shouldDecodeCursorAndPassSnapshotAndKeysetBoundaryToTheQueryPort() {
        Instant firstPageSnapshotAt = NOW.minusSeconds(3600);
        UUID lastItemInstantSeed = UUID.randomUUID();
        String scopeFingerprint = "sha256:" + sha256("");
        String cursorToken = cursorCodec.encode(new TicketTimelineCursor(
            TicketTimelineCursor.CURRENT_VERSION, TICKET_ID.toString(), "employee-123", scopeFingerprint,
            TicketTimelineViewType.EMPLOYEE_PUBLIC_VIEW.name(), TicketTimelineCursor.CURRENT_VISIBILITY_POLICY_VERSION,
            firstPageSnapshotAt, NOW.minusSeconds(7200), 0, "TICKET_CREATED:" + lastItemInstantSeed,
            TicketTimelineSortVersion.CURRENT_VERSION, TicketTimelineCursor.OPERATION, NOW.minusSeconds(60), NOW.plusSeconds(60)
        ));
        when(queryPort.loadGuard(any())).thenReturn(Optional.of(TicketTimelineFixtures.guard("employee-123", TicketTimelineFixtures.DEFAULT_APPLICATION_CODE)));
        when(queryPort.queryTimeline(any(), anyBoolean(), any(), any(), anyInt())).thenReturn(List.of());

        TicketTimelineResult result = service.getTimeline(employeeQuery("employee-123", 50, cursorToken));

        assertThat(result.snapshotAt()).isEqualTo(firstPageSnapshotAt);
        verify(queryPort).queryTimeline(eq(TicketId.of(TICKET_ID)), eq(false), eq(firstPageSnapshotAt), any(), eq(51));
    }

    @Test
    void shouldRejectCursorIssuedForADifferentTicket() {
        String scopeFingerprint = "sha256:" + sha256("");
        String cursorToken = cursorCodec.encode(new TicketTimelineCursor(
            TicketTimelineCursor.CURRENT_VERSION, UUID.randomUUID().toString(), "employee-123", scopeFingerprint,
            TicketTimelineViewType.EMPLOYEE_PUBLIC_VIEW.name(), TicketTimelineCursor.CURRENT_VISIBILITY_POLICY_VERSION,
            NOW, NOW.minusSeconds(60), 0, "TICKET_CREATED:x",
            TicketTimelineSortVersion.CURRENT_VERSION, TicketTimelineCursor.OPERATION, NOW.minusSeconds(60), NOW.plusSeconds(60)
        ));
        when(queryPort.loadGuard(any())).thenReturn(Optional.of(TicketTimelineFixtures.guard("employee-123", TicketTimelineFixtures.DEFAULT_APPLICATION_CODE)));

        TicketTimelineQuery query = employeeQuery("employee-123", 50, cursorToken);

        assertThatThrownBy(() -> service.getTimeline(query)).isInstanceOf(InvalidCursorException.class);
        verify(telemetry).recordTimelineInvalidCursor();
    }

    @Test
    void shouldRejectTamperedCursor() {
        when(queryPort.loadGuard(any())).thenReturn(Optional.of(TicketTimelineFixtures.guard("employee-123", TicketTimelineFixtures.DEFAULT_APPLICATION_CODE)));

        TicketTimelineQuery query = employeeQuery("employee-123", 50, "tampered.cursor");

        assertThatThrownBy(() -> service.getTimeline(query)).isInstanceOf(InvalidCursorException.class);
    }

    private static String sha256(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
