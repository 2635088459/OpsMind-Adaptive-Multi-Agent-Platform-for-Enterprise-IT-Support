package com.opsmind.identity.application.service;

import com.opsmind.identity.application.command.ActivateBreakGlassCommand;
import com.opsmind.identity.application.command.LinkUserIdentityCommand;
import com.opsmind.identity.application.command.ReconcileApprovalOutcomeCommand;
import com.opsmind.identity.application.command.RevokeBreakGlassCommand;
import com.opsmind.identity.application.command.StartSessionCommand;
import com.opsmind.identity.application.exception.BreakGlassActivationDeniedException;
import com.opsmind.identity.application.exception.IdpUnavailableException;
import com.opsmind.identity.application.port.out.UserSessionRepository;
import com.opsmind.identity.domain.breakglass.ApprovalOutcome;
import com.opsmind.identity.domain.breakglass.BreakGlassGrant;
import com.opsmind.identity.domain.breakglass.BreakGlassStatus;
import com.opsmind.identity.domain.role.ResourceScope;
import com.opsmind.identity.domain.session.UserSession;
import com.opsmind.identity.domain.user.IdentityType;
import com.opsmind.identity.support.FakeOidcProviderPort;
import com.opsmind.identity.support.InMemoryAuditPort;
import com.opsmind.identity.support.FakeEventPublisherPort;
import com.opsmind.identity.support.InMemoryBreakGlassGrantRepository;
import com.opsmind.identity.support.InMemoryIdentityMetricsPort;
import com.opsmind.identity.support.InMemoryUserIdentityRepository;
import com.opsmind.identity.support.InMemoryUserSessionRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-UA-019 (Break Glass And Account Recovery). */
@Tag("unit")
class ManageBreakGlassServiceTest {

    private static final String ISSUER = "https://idp.example";
    private static final String SUBJECT = "admin-1";

    private final FixedClockPort clock = new FixedClockPort(Instant.parse("2026-01-01T00:00:00Z"));
    private final InMemoryUserIdentityRepository userIdentityRepository = new InMemoryUserIdentityRepository();
    private final UserSessionRepository userSessionRepository = new InMemoryUserSessionRepository();
    private final ProvisionUserService provisionUserService = new ProvisionUserService(userIdentityRepository, new InMemoryAuditPort(), new FakeEventPublisherPort(), clock);
    private final ManageSessionService sessionService = new ManageSessionService(userSessionRepository, userIdentityRepository, new InMemoryAuditPort(), new FakeEventPublisherPort(), new FakeOidcProviderPort(), new InMemoryIdentityMetricsPort(), clock);
    private final FakeEventPublisherPort eventPublisherPort = new FakeEventPublisherPort();
    private final FakeOidcProviderPort oidcProviderPort = new FakeOidcProviderPort();
    private final ManageBreakGlassService service = new ManageBreakGlassService(
        new InMemoryBreakGlassGrantRepository(), userIdentityRepository, userSessionRepository, new InMemoryAuditPort(), eventPublisherPort, oidcProviderPort, clock
    );

    private UserSession sessionWithAssurance(String acr, List<String> amr) {
        provisionUserService.link(new LinkUserIdentityCommand("tenant-1", ISSUER, SUBJECT, "admin", "Admin", null, IdentityType.HUMAN, "corr-setup"));
        return sessionService.start(new StartSessionCommand(
            "tenant-1", ISSUER, SUBJECT, "idp-hash", "token-hash", "client-1", acr, amr, clock.now(), "device-hash", Duration.ofHours(1), "corr-session"
        ));
    }

    private ActivateBreakGlassCommand command(String sessionId, String approvalReference, String reason, Duration ttl, String requiredAcr, List<String> requiredMethods) {
        return new ActivateBreakGlassCommand(
            "tenant-1", ISSUER, SUBJECT, sessionId, ResourceScope.tenantWide(), approvalReference, reason, requiredAcr, requiredMethods, ttl, "corr-1"
        );
    }

    @Test
    void activatesWhenAllPreconditionsAreMet() {
        UserSession session = sessionWithAssurance("AAL2", List.of("pwd", "otp"));

        BreakGlassGrant grant = service.activate(command(session.userSessionId(), "approval-ref-1", "prod incident", Duration.ofHours(1), "AAL2", List.of("otp")));

        assertThat(grant.status()).isEqualTo(BreakGlassStatus.ACTIVE);
        assertThat(grant.approvalReference()).isEqualTo("approval-ref-1");
        assertThat(grant.expiresAt()).isEqualTo(clock.now().plus(Duration.ofHours(1)));
    }

    /** SPEC-UA-032 (10-failure-handling: "Keycloak unavailable ... sensitive actions return 503/fail closed"). */
    @Test
    void activateFailsClosedWhenTheIdpIsUnavailable() {
        UserSession session = sessionWithAssurance("AAL2", List.of("pwd", "otp"));
        oidcProviderPort.setAvailable(false);

        assertThatThrownBy(() -> service.activate(command(session.userSessionId(), "approval-ref-1", "prod incident", Duration.ofHours(1), "AAL2", List.of("otp"))))
            .isInstanceOf(IdpUnavailableException.class);
        assertThat(eventPublisherPort.published()).isEmpty();
    }

    /** SPEC-UA-029: "break-glass use" (12-observability's own alert list) publishes a real identity.security.alert.v1. */
    @Test
    void activationPublishesASecurityAlert() {
        UserSession session = sessionWithAssurance("AAL2", List.of("pwd", "otp"));

        BreakGlassGrant grant = service.activate(command(session.userSessionId(), "approval-ref-1", "prod incident", Duration.ofHours(1), "AAL2", List.of("otp")));

        assertThat(eventPublisherPort.published()).hasSize(1);
        FakeEventPublisherPort.Published alert = eventPublisherPort.published().get(0);
        assertThat(alert.eventType()).isEqualTo("identity.security.alert.v1");
        assertThat(alert.aggregateType()).isEqualTo("BreakGlassGrant");
        assertThat(alert.aggregateId()).isEqualTo(grant.breakGlassGrantId());
        assertThat(alert.payloadJson()).contains("\"alertType\":\"BREAK_GLASS_ACTIVATED\"").contains("\"severity\":\"HIGH\"");
    }

    /** A denied predecessor approval (deny() path) never activates, so it never publishes anything either. */
    @Test
    void aDeniedActivationPublishesNoSecurityAlert() {
        UserSession session = sessionWithAssurance("AAL2", List.of("otp"));

        assertThatThrownBy(() -> service.activate(command(session.userSessionId(), null, "prod incident", Duration.ofHours(1), null, List.of())))
            .isInstanceOf(BreakGlassActivationDeniedException.class);

        assertThat(eventPublisherPort.published()).isEmpty();
    }

    @Test
    void deniesWhenNoApprovalReferenceIsGiven() {
        UserSession session = sessionWithAssurance("AAL2", List.of("otp"));

        assertThatThrownBy(() -> service.activate(command(session.userSessionId(), null, "prod incident", Duration.ofHours(1), null, List.of())))
            .isInstanceOf(BreakGlassActivationDeniedException.class);
    }

    @Test
    void deniesWhenNoReasonIsGiven() {
        UserSession session = sessionWithAssurance("AAL2", List.of("otp"));

        assertThatThrownBy(() -> service.activate(command(session.userSessionId(), "approval-ref-1", " ", Duration.ofHours(1), null, List.of())))
            .isInstanceOf(BreakGlassActivationDeniedException.class);
    }

    @Test
    void deniesADurationBeyondTheBoundedMaximum() {
        UserSession session = sessionWithAssurance("AAL2", List.of("otp"));

        assertThatThrownBy(() -> service.activate(command(session.userSessionId(), "approval-ref-1", "prod incident", ManageBreakGlassService.MAX_TTL.plusMinutes(1), null, List.of())))
            .isInstanceOf(BreakGlassActivationDeniedException.class);
    }

    @Test
    void deniesWhenNoSessionIsGiven() {
        provisionUserService.link(new LinkUserIdentityCommand("tenant-1", ISSUER, SUBJECT, "admin", "Admin", null, IdentityType.HUMAN, "corr-setup"));

        assertThatThrownBy(() -> service.activate(command(null, "approval-ref-1", "prod incident", Duration.ofHours(1), null, List.of())))
            .isInstanceOf(BreakGlassActivationDeniedException.class);
    }

    @Test
    void deniesWhenTheSessionDoesNotMeetTheRequiredAssuranceLevel() {
        UserSession session = sessionWithAssurance("urn:mace:acr:0", List.of("pwd"));

        assertThatThrownBy(() -> service.activate(command(session.userSessionId(), "approval-ref-1", "prod incident", Duration.ofHours(1), "AAL2", List.of("otp"))))
            .isInstanceOf(BreakGlassActivationDeniedException.class);
    }

    @Test
    void revokeEndsAnActiveGrantEarly() {
        UserSession session = sessionWithAssurance("AAL2", List.of("otp"));
        BreakGlassGrant grant = service.activate(command(session.userSessionId(), "approval-ref-1", "prod incident", Duration.ofHours(1), null, List.of()));

        BreakGlassGrant revoked = service.revoke(new RevokeBreakGlassCommand(grant.breakGlassGrantId(), "security-admin", "misuse suspected", "corr-2"));

        assertThat(revoked.status()).isEqualTo(BreakGlassStatus.REVOKED);
    }

    /** SPEC-UA-029: a routine admin-initiated revoke is not itself an alert-worthy signal — only activation is (the alert from the earlier activate() call is the only one present). */
    @Test
    void aRoutineAdminRevokePublishesNoAdditionalSecurityAlert() {
        UserSession session = sessionWithAssurance("AAL2", List.of("otp"));
        BreakGlassGrant grant = service.activate(command(session.userSessionId(), "approval-ref-1", "prod incident", Duration.ofHours(1), null, List.of()));

        service.revoke(new RevokeBreakGlassCommand(grant.breakGlassGrantId(), "security-admin", "misuse suspected", "corr-2"));

        assertThat(eventPublisherPort.published()).hasSize(1);
        assertThat(eventPublisherPort.published().get(0).payloadJson()).contains("BREAK_GLASS_ACTIVATED");
    }

    @Test
    void reconcileExpiresActiveGrantsPastTheirOwnBoundedWindow() {
        UserSession session = sessionWithAssurance("AAL2", List.of("otp"));
        BreakGlassGrant grant = service.activate(command(session.userSessionId(), "approval-ref-1", "prod incident", Duration.ofHours(1), null, List.of()));

        assertThat(service.reconcileExpired()).isZero();

        clock.advanceTo(grant.expiresAt());
        assertThat(service.reconcileExpired()).isEqualTo(1);
        assertThat(service.findById(grant.breakGlassGrantId()).status()).isEqualTo(BreakGlassStatus.EXPIRED);
    }

    /** SPEC-UA-028: a domain-06 DENIED outcome revokes any still-ACTIVE grant that referenced this approval. */
    @Test
    void reconcileApprovalOutcomeRevokesAnActiveGrantWhenTheUnderlyingApprovalWasDenied() {
        UserSession session = sessionWithAssurance("AAL2", List.of("otp"));
        BreakGlassGrant grant = service.activate(command(session.userSessionId(), "approval-ref-1", "prod incident", Duration.ofHours(1), null, List.of()));

        service.reconcileApprovalOutcome(new ReconcileApprovalOutcomeCommand("approval-ref-1", ApprovalOutcome.DENIED, "corr-async"));

        assertThat(service.findById(grant.breakGlassGrantId()).status()).isEqualTo(BreakGlassStatus.REVOKED);
        assertThat(eventPublisherPort.published()).hasSize(2); // activation's own alert, then the reconciliation-triggered one
        assertThat(eventPublisherPort.published().get(1).payloadJson())
            .contains("\"alertType\":\"BREAK_GLASS_REVOKED_AFTER_APPROVAL_OUTCOME\"").contains("\"reasonCode\":\"DENIED\"");
    }

    /** Same real transition for EXPIRED as for DENIED. */
    @Test
    void reconcileApprovalOutcomeRevokesAnActiveGrantWhenTheUnderlyingApprovalExpired() {
        UserSession session = sessionWithAssurance("AAL2", List.of("otp"));
        BreakGlassGrant grant = service.activate(command(session.userSessionId(), "approval-ref-2", "prod incident", Duration.ofHours(1), null, List.of()));

        service.reconcileApprovalOutcome(new ReconcileApprovalOutcomeCommand("approval-ref-2", ApprovalOutcome.EXPIRED, "corr-async"));

        assertThat(service.findById(grant.breakGlassGrantId()).status()).isEqualTo(BreakGlassStatus.REVOKED);
    }

    /** GRANTED is a no-op — the grant was already trusted at activation time, nothing to reconcile. */
    @Test
    void reconcileApprovalOutcomeDoesNothingWhenTheUnderlyingApprovalWasGranted() {
        UserSession session = sessionWithAssurance("AAL2", List.of("otp"));
        BreakGlassGrant grant = service.activate(command(session.userSessionId(), "approval-ref-3", "prod incident", Duration.ofHours(1), null, List.of()));

        service.reconcileApprovalOutcome(new ReconcileApprovalOutcomeCommand("approval-ref-3", ApprovalOutcome.GRANTED, "corr-async"));

        assertThat(service.findById(grant.breakGlassGrantId()).status()).isEqualTo(BreakGlassStatus.ACTIVE);
    }

    /** No grant ever referenced this approval — silently does nothing, never throws. */
    @Test
    void reconcileApprovalOutcomeIsANoOpWhenNoGrantReferencesTheApproval() {
        service.reconcileApprovalOutcome(new ReconcileApprovalOutcomeCommand("approval-ref-unrelated", ApprovalOutcome.DENIED, "corr-async"));
    }

    /** An already-revoked grant is left alone — never re-revoked, never throws IllegalBreakGlassTransitionException. */
    @Test
    void reconcileApprovalOutcomeDoesNotReRevokeAGrantThatIsAlreadyRevoked() {
        UserSession session = sessionWithAssurance("AAL2", List.of("otp"));
        BreakGlassGrant grant = service.activate(command(session.userSessionId(), "approval-ref-4", "prod incident", Duration.ofHours(1), null, List.of()));
        service.revoke(new RevokeBreakGlassCommand(grant.breakGlassGrantId(), "security-admin", "misuse suspected", "corr-2"));

        service.reconcileApprovalOutcome(new ReconcileApprovalOutcomeCommand("approval-ref-4", ApprovalOutcome.DENIED, "corr-async"));

        BreakGlassGrant unchanged = service.findById(grant.breakGlassGrantId());
        assertThat(unchanged.status()).isEqualTo(BreakGlassStatus.REVOKED);
        assertThat(unchanged.revokedBy()).isEqualTo("security-admin");
    }
}
