package com.opsmind.identity.application.service;

import com.opsmind.identity.application.command.ChangeUserIdentityStatusCommand;
import com.opsmind.identity.application.command.LinkUserIdentityCommand;
import com.opsmind.identity.application.exception.UserIdentityNotFoundException;
import com.opsmind.identity.domain.audit.IdentityAuditAction;
import com.opsmind.identity.domain.audit.IdentityAuditRecord;
import com.opsmind.identity.domain.user.IdentityType;
import com.opsmind.identity.domain.user.UserIdentity;
import com.opsmind.identity.domain.user.UserStatus;
import com.opsmind.identity.infrastructure.audit.InMemoryAuditPort;
import com.opsmind.identity.infrastructure.persistence.adapter.InMemoryUserIdentityRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class ProvisionUserServiceTest {

    private final FixedClockPort clock = new FixedClockPort(Instant.parse("2026-01-01T00:00:00Z"));
    private final InMemoryAuditPort auditPort = new InMemoryAuditPort();
    private final ProvisionUserService service = new ProvisionUserService(new InMemoryUserIdentityRepository(), auditPort, clock);

    private LinkUserIdentityCommand command(String correlationId) {
        return new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-1", "alice", "Alice", "alice@example.com", IdentityType.HUMAN, correlationId);
    }

    @Test
    void linkIsIdempotentOnExternalSubject() {
        UserIdentity first = service.link(command("corr-1"));
        UserIdentity second = service.link(command("corr-2"));

        assertThat(second.userIdentityId()).isEqualTo(first.userIdentityId());

        List<IdentityAuditRecord> firstCallAudit = auditPort.findByCorrelationId("corr-1");
        assertThat(firstCallAudit).hasSize(1);
        assertThat(firstCallAudit.get(0).action()).isEqualTo(IdentityAuditAction.USER_IDENTITY_LINKED);
    }

    @Test
    void changeStatusRoundTrips() {
        UserIdentity linked = service.link(command("corr-1"));

        UserIdentity disabled = service.changeStatus(new ChangeUserIdentityStatusCommand(linked.userIdentityId(), UserStatus.DISABLED, "policy", "corr-2"));
        assertThat(disabled.status()).isEqualTo(UserStatus.DISABLED);

        UserIdentity reenabled = service.changeStatus(new ChangeUserIdentityStatusCommand(linked.userIdentityId(), UserStatus.ACTIVE, null, "corr-3"));
        assertThat(reenabled.status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void findByIdThrowsWhenMissing() {
        assertThatThrownBy(() -> service.findById("missing")).isInstanceOf(UserIdentityNotFoundException.class);
    }
}
