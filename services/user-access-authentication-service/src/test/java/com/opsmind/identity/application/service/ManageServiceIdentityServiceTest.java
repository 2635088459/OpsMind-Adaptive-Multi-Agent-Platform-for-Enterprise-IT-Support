package com.opsmind.identity.application.service;

import com.opsmind.identity.application.command.DisableServiceIdentityCommand;
import com.opsmind.identity.application.command.RegisterServiceIdentityCommand;
import com.opsmind.identity.application.exception.ServiceIdentityNotFoundException;
import com.opsmind.identity.domain.workload.ServiceIdentity;
import com.opsmind.identity.domain.workload.ServiceIdentityStatus;
import com.opsmind.identity.infrastructure.audit.InMemoryAuditPort;
import com.opsmind.identity.infrastructure.persistence.adapter.InMemoryServiceIdentityRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class ManageServiceIdentityServiceTest {

    private final FixedClockPort clock = new FixedClockPort(Instant.parse("2026-01-01T00:00:00Z"));
    private final ManageServiceIdentityService service = new ManageServiceIdentityService(new InMemoryServiceIdentityRepository(), new InMemoryAuditPort(), clock);

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
}
