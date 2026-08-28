package com.opsmind.identity.application.service;

import com.opsmind.identity.application.command.ValidateWorkloadIdentityCommand;
import com.opsmind.identity.application.dto.WorkloadIdentityView;
import com.opsmind.identity.application.exception.WorkloadIdentityNotTrustedException;
import com.opsmind.identity.application.port.in.ValidateWorkloadIdentityUseCase;
import com.opsmind.identity.application.port.out.AuditPort;
import com.opsmind.identity.application.port.out.ClockPort;
import com.opsmind.identity.application.port.out.ServiceIdentityRepository;
import com.opsmind.identity.domain.audit.AuditOutcome;
import com.opsmind.identity.domain.audit.IdentityAuditAction;
import com.opsmind.identity.domain.audit.IdentityAuditRecord;
import com.opsmind.identity.domain.shared.CorrelationId;
import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.domain.user.ExternalSubject;
import com.opsmind.identity.domain.workload.ServiceIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * SPEC-UA-010 (Workload And Service Identity — 11-security §Tokens and
 * protocols: "Workloads use client credentials or mTLS with separate
 * audiences/scopes and cannot impersonate a human sub"; 04-use-cases
 * §Workload identity: "Client-credentials/mTLS identity → audience/scope
 * validation | Service identities cannot impersonate human actors"). Turns
 * the {@link ServiceIdentity} registry SPEC-UA-001/002/003 already built
 * (registration/disable/retire, but zero real trust-decision caller) into a
 * real, INV-UA-002 deny-by-default check: a bearer JWT is only ever trusted
 * as a workload once it resolves to a currently {@code ACTIVE} and in-window
 * {@code ServiceIdentity} whose own registered audience/scope allow-lists
 * the token's actual {@code aud}/{@code scope} claims intersect — the same
 * "empty allow-list means unrestricted, non-empty means must intersect"
 * convention {@link com.opsmind.identity.config.OidcIssuerProperties}'s own
 * audience validator already established for human tokens. A workload
 * identity's own {@code (issuer, subject)} is never a human {@code
 * UserIdentity}'s — this method never touches {@code UserIdentityRepository}
 * at all, so a workload token can never be mistaken for a human one.
 *
 * <p>08-transaction-and-outbox: this is a lightweight liveness/trust check,
 * not a state transition with cross-domain effects — {@link
 * ServiceIdentity#recordSeen} + audit commit together in one transaction,
 * same as every other write path, but nothing is published to the outbox
 * (mirrors {@code ManageSessionService#refresh}'s own no-event precedent).
 */
@Service
public class ValidateWorkloadIdentityService implements ValidateWorkloadIdentityUseCase {

    private final ServiceIdentityRepository serviceIdentityRepository;
    private final AuditPort auditPort;
    private final ClockPort clock;

    public ValidateWorkloadIdentityService(ServiceIdentityRepository serviceIdentityRepository, AuditPort auditPort, ClockPort clock) {
        this.serviceIdentityRepository = serviceIdentityRepository;
        this.auditPort = auditPort;
        this.clock = clock;
    }

    @Override
    @Transactional
    public WorkloadIdentityView validate(ValidateWorkloadIdentityCommand command) {
        ExternalSubject externalSubject = new ExternalSubject(command.issuer(), command.subject());
        Instant now = clock.now();

        Optional<ServiceIdentity> found = serviceIdentityRepository.findByExternalSubject(command.tenantId(), externalSubject);
        if (found.isEmpty()) {
            deny(command, externalSubject, null, "no service identity is registered for this subject");
        }
        ServiceIdentity serviceIdentity = found.get();

        if (!serviceIdentity.isValid(now)) {
            deny(command, externalSubject, serviceIdentity, "service identity status is " + serviceIdentity.status() + " or outside its validity window");
        }
        if (!serviceIdentity.allowedAudiences().isEmpty()
            && command.tokenAudiences().stream().noneMatch(serviceIdentity.allowedAudiences()::contains)) {
            deny(command, externalSubject, serviceIdentity, "token audience is not in the registered allow-list");
        }
        if (!serviceIdentity.allowedScopes().isEmpty()
            && command.tokenScopes().stream().noneMatch(serviceIdentity.allowedScopes()::contains)) {
            deny(command, externalSubject, serviceIdentity, "token scope is not in the registered allow-list");
        }

        ServiceIdentity saved = serviceIdentityRepository.save(serviceIdentity.recordSeen(now));
        auditPort.record(IdentityAuditRecord.record(
            UUID.randomUUID().toString(), saved.tenantId(), IdentityAuditAction.SERVICE_IDENTITY_VALIDATED, null,
            saved.serviceIdentityId(), null, AuditOutcome.SUCCESS, null, new CorrelationId(command.correlationId()), now
        ));
        return WorkloadIdentityView.from(saved);
    }

    /** Always audits {@code DENIED} then throws {@link WorkloadIdentityNotTrustedException}. */
    private void deny(ValidateWorkloadIdentityCommand command, ExternalSubject externalSubject, ServiceIdentity maybeFound, String reason) {
        auditPort.record(IdentityAuditRecord.record(
            UUID.randomUUID().toString(), new TenantId(command.tenantId()), IdentityAuditAction.SERVICE_IDENTITY_VALIDATED, null,
            maybeFound == null ? externalSubject.subject() : maybeFound.serviceIdentityId(), null, AuditOutcome.DENIED, reason,
            new CorrelationId(command.correlationId()), clock.now()
        ));
        throw new WorkloadIdentityNotTrustedException(externalSubject.subject(), reason);
    }
}
