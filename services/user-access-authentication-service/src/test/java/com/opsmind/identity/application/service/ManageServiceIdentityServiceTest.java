package com.opsmind.identity.application.service;

import com.opsmind.identity.application.command.DisableServiceIdentityCommand;
import com.opsmind.identity.application.command.RegisterServiceIdentityCommand;
import com.opsmind.identity.application.exception.ServiceIdentityNotFoundException;
import com.opsmind.identity.domain.workload.ServiceIdentity;
import com.opsmind.identity.domain.workload.ServiceIdentityStatus;
import com.opsmind.identity.support.InMemoryAuditPort;
import com.opsmind.identity.support.FakeEventPublisherPort;
import com.opsmind.identity.support.InMemoryServiceIdentityRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class ManageServiceIdentityServiceTest {

    private final FixedClockPort clock = new FixedClockPort(Instant.parse("2026-01-01T00:00:00Z"));
    private final ManageServiceIdentityService service = new ManageServiceIdentityService(new InMemoryServiceIdentityRepository(), new InMemoryAuditPort(), new FakeEventPublisherPort(), clock);

    private RegisterServiceIdentityCommand command() {
        return new RegisterServiceIdentityCommand("tenant-1", "https://idp.example", "svc-sub-1", "client-1", "ticket-workflow-service", List.of("identity-api"), List.of("identity:read"), null, null, "corr-1");
    }

    @Test
    void registerIsIdempotentOnExternalSubject() {
        ServiceIdentity first = service.register(command());
        ServiceIdentity second = service.register(command());

        assertThat(second.serviceIdentityId()).isEqualTo(first.serviceIdentityId());
        assertThat(first.status()).isEqualTo(ServiceIdentityStatus.ACTIVE);
    }

    @Test
    void disableTransitionsToDisabled() {
        ServiceIdentity registered = service.register(command());

        ServiceIdentity disabled = service.disable(new DisableServiceIdentityCommand(registered.serviceIdentityId(), "corr-2"));

        assertThat(disabled.status()).isEqualTo(ServiceIdentityStatus.DISABLED);
    }

    @Test
    void findByIdThrowsWhenMissing() {
        assertThatThrownBy(() -> service.findById("missing")).isInstanceOf(ServiceIdentityNotFoundException.class);
    }

    @Test
    void reconcileRetiresActiveIdentitiesPastTheirOwnValidUntil() {
        Instant validUntil = clock.now().plusSeconds(3600);
        RegisterServiceIdentityCommand scoped = new RegisterServiceIdentityCommand(
            "tenant-1", "https://idp.example", "svc-sub-2", "client-2", "ticket-workflow-service", List.of("identity-api"), List.of("identity:read"), null, validUntil, "corr-1"
        );
        ServiceIdentity registered = service.register(scoped);

        assertThat(service.reconcileRetired()).isZero();

        clock.advanceTo(validUntil);
        assertThat(service.reconcileRetired()).isEqualTo(1);
        assertThat(service.findById(registered.serviceIdentityId()).status()).isEqualTo(ServiceIdentityStatus.RETIRED);
    }
}
