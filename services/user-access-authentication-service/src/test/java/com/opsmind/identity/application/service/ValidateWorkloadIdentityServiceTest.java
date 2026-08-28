package com.opsmind.identity.application.service;

import com.opsmind.identity.application.command.DisableServiceIdentityCommand;
import com.opsmind.identity.application.command.RegisterServiceIdentityCommand;
import com.opsmind.identity.application.command.ValidateWorkloadIdentityCommand;
import com.opsmind.identity.application.dto.WorkloadIdentityView;
import com.opsmind.identity.application.exception.WorkloadIdentityNotTrustedException;
import com.opsmind.identity.domain.workload.ServiceIdentity;
import com.opsmind.identity.support.InMemoryAuditPort;
import com.opsmind.identity.support.FakeEventPublisherPort;
import com.opsmind.identity.support.InMemoryServiceIdentityRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-UA-010 (11-security §Tokens and protocols: "Workloads use client credentials or mTLS with separate audiences/scopes and cannot impersonate a human sub"). */
@Tag("unit")
class ValidateWorkloadIdentityServiceTest {

    private final FixedClockPort clock = new FixedClockPort(Instant.parse("2026-01-01T00:00:00Z"));
    private final InMemoryServiceIdentityRepository serviceIdentityRepository = new InMemoryServiceIdentityRepository();
    private final ManageServiceIdentityService registerService = new ManageServiceIdentityService(serviceIdentityRepository, new InMemoryAuditPort(), new FakeEventPublisherPort(), clock);
    private final ValidateWorkloadIdentityService service = new ValidateWorkloadIdentityService(serviceIdentityRepository, new InMemoryAuditPort(), clock);

    private ServiceIdentity registered(List<String> allowedAudiences, List<String> allowedScopes) {
        return registerService.register(new RegisterServiceIdentityCommand(
            "tenant-1", "https://idp.example", "svc-sub-1", "client-1", "ticket-workflow-service",
            allowedAudiences, allowedScopes, null, null, "corr-setup"
        ));
    }

    private ValidateWorkloadIdentityCommand command(List<String> tokenAudiences, List<String> tokenScopes) {
        return new ValidateWorkloadIdentityCommand("tenant-1", "https://idp.example", "svc-sub-1", tokenAudiences, tokenScopes, "corr-1");
    }

    @Test
    void validatesARegisteredActiveIdentityWithMatchingAudienceAndScope() {
        registered(List.of("identity-api"), List.of("identity:read"));

        WorkloadIdentityView view = service.validate(command(List.of("identity-api"), List.of("identity:read")));

        assertThat(view.serviceIdentityId()).isNotBlank();
        assertThat(view.serviceName()).isEqualTo("ticket-workflow-service");
    }

    @Test
    void recordsSeenOnEverySuccessfulValidation() {
        ServiceIdentity original = registered(List.of(), List.of());
        assertThat(original.lastSeenAt()).isNull();

        service.validate(command(List.of(), List.of()));

        assertThat(serviceIdentityRepository.findById(original.serviceIdentityId()).orElseThrow().lastSeenAt()).isEqualTo(clock.now());
    }

    @Test
    void emptyAllowListsMeanNoRestrictionOnAudienceOrScope() {
        registered(List.of(), List.of());

        WorkloadIdentityView view = service.validate(command(List.of("anything"), List.of("anything")));

        assertThat(view).isNotNull();
    }

    @Test
    void deniesWhenNoServiceIdentityIsRegisteredForTheSubject() {
        assertThatThrownBy(() -> service.validate(command(List.of(), List.of())))
            .isInstanceOf(WorkloadIdentityNotTrustedException.class);
    }

    @Test
    void deniesADisabledServiceIdentity() {
        ServiceIdentity registered = registered(List.of(), List.of());
        registerService.disable(new DisableServiceIdentityCommand(registered.serviceIdentityId(), "corr-disable"));

        assertThatThrownBy(() -> service.validate(command(List.of(), List.of())))
            .isInstanceOf(WorkloadIdentityNotTrustedException.class);
    }

    @Test
    void deniesAnIdentityNotYetInItsValidityWindow() {
        registerService.register(new RegisterServiceIdentityCommand(
            "tenant-1", "https://idp.example", "svc-sub-1", "client-1", "ticket-workflow-service",
            List.of(), List.of(), clock.now().plusSeconds(3600), null, "corr-setup"
        ));

        assertThatThrownBy(() -> service.validate(command(List.of(), List.of())))
            .isInstanceOf(WorkloadIdentityNotTrustedException.class);
    }

    @Test
    void deniesATokenAudienceOutsideTheRegisteredAllowList() {
        registered(List.of("identity-api"), List.of());

        assertThatThrownBy(() -> service.validate(command(List.of("some-other-api"), List.of())))
            .isInstanceOf(WorkloadIdentityNotTrustedException.class);
    }

    @Test
    void deniesATokenScopeOutsideTheRegisteredAllowList() {
        registered(List.of(), List.of("identity:read"));

        assertThatThrownBy(() -> service.validate(command(List.of(), List.of("identity:admin"))))
            .isInstanceOf(WorkloadIdentityNotTrustedException.class);
    }

    @Test
    void neverConsultsAUserIdentityAtAllSoAWorkloadCanNeverBeMistakenForAHuman() {
        // No UserIdentityRepository is even injected into ValidateWorkloadIdentityService — a
        // workload token can never resolve to (or be confused with) a human UserIdentity by construction.
        registered(List.of(), List.of());

        assertThat(service.validate(command(List.of(), List.of()))).isNotNull();
    }
}
