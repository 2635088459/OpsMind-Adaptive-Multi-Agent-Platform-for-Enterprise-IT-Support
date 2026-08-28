package com.opsmind.identity.application.service;

import com.opsmind.identity.application.command.ChangeUserIdentityStatusCommand;
import com.opsmind.identity.application.command.LinkUserIdentityCommand;
import com.opsmind.identity.application.command.SyncUserIdentityCommand;
import com.opsmind.identity.application.exception.UserIdentityNotFoundException;
import com.opsmind.identity.domain.audit.IdentityAuditAction;
import com.opsmind.identity.domain.audit.IdentityAuditRecord;
import com.opsmind.identity.domain.user.IdentityType;
import com.opsmind.identity.domain.user.UserIdentity;
import com.opsmind.identity.domain.user.UserStatus;
import com.opsmind.identity.support.InMemoryAuditPort;
import com.opsmind.identity.support.FakeEventPublisherPort;
import com.opsmind.identity.support.InMemoryUserIdentityRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class ProvisionUserServiceTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private final FixedClockPort clock = new FixedClockPort(START);
    private final InMemoryAuditPort auditPort = new InMemoryAuditPort();
    private final InMemoryUserIdentityRepository userIdentityRepository = new InMemoryUserIdentityRepository();
    private final ProvisionUserService service = new ProvisionUserService(userIdentityRepository, auditPort, new FakeEventPublisherPort(), clock);

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

    /** SPEC-UA-008 (04-use-cases §User synchronization; 10-failure-handling: "upstream version/time prevents stale overwrite"). */
    @Test
    void syncAppliesANewerProfileVersionAndAuditsIt() {
        UserIdentity linked = service.link(command("corr-1"));

        UserIdentity synced = service.sync(new SyncUserIdentityCommand(linked.userIdentityId(), "alice2", "Alice Two", "alice2@example.com", 5, "corr-2"));

        assertThat(synced.username()).isEqualTo("alice2");
        assertThat(synced.displayName()).isEqualTo("Alice Two");
        assertThat(synced.email()).isEqualTo("alice2@example.com");
        assertThat(synced.profileVersion()).isEqualTo(5);
        List<IdentityAuditRecord> auditRecords = auditPort.findByCorrelationId("corr-2");
        assertThat(auditRecords).hasSize(1);
        assertThat(auditRecords.get(0).action()).isEqualTo(IdentityAuditAction.USER_IDENTITY_SYNCED);
    }

    @Test
    void syncIsANoOpWhenTheProfileVersionIsNotNewer() {
        UserIdentity linked = service.link(command("corr-1"));
        UserIdentity firstSync = service.sync(new SyncUserIdentityCommand(linked.userIdentityId(), "alice2", "Alice Two", "alice2@example.com", 5, "corr-2"));

        UserIdentity staleSync = service.sync(new SyncUserIdentityCommand(linked.userIdentityId(), "stale-name", "Stale", "stale@example.com", 5, "corr-3"));

        assertThat(staleSync.username()).isEqualTo("alice2");
        assertThat(staleSync.profileVersion()).isEqualTo(5);
        assertThat(staleSync.version()).isEqualTo(firstSync.version());
    }

    @Test
    void syncThrowsWhenTheUserIdentityIsMissing() {
        assertThatThrownBy(() -> service.sync(new SyncUserIdentityCommand("missing", "alice", "Alice", "alice@example.com", 1, "corr-1")))
            .isInstanceOf(UserIdentityNotFoundException.class);
    }

    /** SPEC-UA-031 (07-data-model: "Email/display name may be encrypted and erased by retention"). */
    @Test
    void reconcilePrivacyRetentionRedactsAnIdentityPastItsRetentionWindow() {
        UserIdentity linked = service.link(command("corr-1"));
        UserIdentity deprovisioned = service.changeStatus(new ChangeUserIdentityStatusCommand(linked.userIdentityId(), UserStatus.DEPROVISIONED, "offboarded", "corr-2"));
        assertThat(deprovisioned.status()).isEqualTo(UserStatus.DEPROVISIONED);

        clock.advanceTo(START.plus(ProvisionUserService.PII_RETENTION_PERIOD).plusSeconds(1));
        int redactedCount = service.reconcilePrivacyRetention();

        assertThat(redactedCount).isEqualTo(1);
        UserIdentity found = service.findById(linked.userIdentityId());
        assertThat(found.username()).isNull();
        assertThat(found.displayName()).isNull();
        assertThat(found.email()).isNull();
        assertThat(found.piiRedactedAt()).isNotNull();
    }

    @Test
    void reconcilePrivacyRetentionAuditsEachRedaction() {
        UserIdentity linked = service.link(command("corr-1"));
        service.changeStatus(new ChangeUserIdentityStatusCommand(linked.userIdentityId(), UserStatus.DEPROVISIONED, "offboarded", "corr-2"));
        clock.advanceTo(START.plus(ProvisionUserService.PII_RETENTION_PERIOD).plusSeconds(1));

        service.reconcilePrivacyRetention();

        assertThat(auditPort.all())
            .filteredOn(r -> r.action() == IdentityAuditAction.USER_IDENTITY_PII_REDACTED)
            .hasSize(1)
            .allSatisfy(r -> assertThat(r.subjectRef()).isEqualTo(linked.userIdentityId()));
    }

    @Test
    void reconcilePrivacyRetentionSkipsAnIdentityStillWithinTheRetentionWindow() {
        UserIdentity linked = service.link(command("corr-1"));
        service.changeStatus(new ChangeUserIdentityStatusCommand(linked.userIdentityId(), UserStatus.DEPROVISIONED, "offboarded", "corr-2"));

        int redactedCount = service.reconcilePrivacyRetention();

        assertThat(redactedCount).isZero();
        assertThat(service.findById(linked.userIdentityId()).username()).isEqualTo("alice");
    }

    @Test
    void reconcilePrivacyRetentionNeverTouchesAStillActiveIdentity() {
        service.link(command("corr-1"));

        clock.advanceTo(START.plus(ProvisionUserService.PII_RETENTION_PERIOD).plusSeconds(1));
        int redactedCount = service.reconcilePrivacyRetention();

        assertThat(redactedCount).isZero();
    }

    @Test
    void reconcilePrivacyRetentionIsIdempotentAcrossRuns() {
        UserIdentity linked = service.link(command("corr-1"));
        service.changeStatus(new ChangeUserIdentityStatusCommand(linked.userIdentityId(), UserStatus.DEPROVISIONED, "offboarded", "corr-2"));
        clock.advanceTo(START.plus(ProvisionUserService.PII_RETENTION_PERIOD).plusSeconds(1));

        assertThat(service.reconcilePrivacyRetention()).isEqualTo(1);
        assertThat(service.reconcilePrivacyRetention()).isZero();
    }
}
