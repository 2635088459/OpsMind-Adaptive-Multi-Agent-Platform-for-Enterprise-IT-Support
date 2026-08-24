package com.opsmind.identity.application.service;

import com.opsmind.identity.application.command.DisableServiceIdentityCommand;
import com.opsmind.identity.application.command.RegisterServiceIdentityCommand;
import com.opsmind.identity.application.exception.ServiceIdentityNotFoundException;
import com.opsmind.identity.application.port.in.ManageServiceIdentityUseCase;
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

import java.util.Optional;
import java.util.UUID;

/** UC-UA-008: register/disable a {@link ServiceIdentity}. Real client-credentials/mTLS validation is SPEC-UA-010's job. */
@Service
public class ManageServiceIdentityService implements ManageServiceIdentityUseCase {

    private final ServiceIdentityRepository serviceIdentityRepository;
    private final AuditPort auditPort;
    private final ClockPort clock;

    public ManageServiceIdentityService(ServiceIdentityRepository serviceIdentityRepository, AuditPort auditPort, ClockPort clock) {
        this.serviceIdentityRepository = serviceIdentityRepository;
        this.auditPort = auditPort;
        this.clock = clock;
    }

    @Override
    public ServiceIdentity register(RegisterServiceIdentityCommand command) {
        TenantId tenantId = new TenantId(command.tenantId());
        ExternalSubject externalSubject = new ExternalSubject(command.issuer(), command.subject());
        Optional<ServiceIdentity> existing = serviceIdentityRepository.findByExternalSubject(command.tenantId(), externalSubject);
        if (existing.isPresent()) {
            return existing.get();
        }

        ServiceIdentity registered = ServiceIdentity.register(
            UUID.randomUUID().toString(), tenantId, externalSubject, command.clientId(), command.serviceName(),
            command.allowedAudiences(), command.allowedScopes(), command.validFrom(), command.validUntil(), clock.now()
        );
        ServiceIdentity saved = serviceIdentityRepository.save(registered);
        auditPort.record(IdentityAuditRecord.record(
            UUID.randomUUID().toString(), tenantId, IdentityAuditAction.SERVICE_IDENTITY_REGISTERED, null,
            saved.serviceIdentityId(), null, AuditOutcome.SUCCESS, "service=" + saved.serviceName(),
            new CorrelationId(command.correlationId()), clock.now()
        ));
        return saved;
    }

    @Override
    public ServiceIdentity disable(DisableServiceIdentityCommand command) {
        ServiceIdentity current = findByIdOrThrow(command.serviceIdentityId());
        ServiceIdentity saved = serviceIdentityRepository.save(current.disable(clock.now()));
        auditPort.record(IdentityAuditRecord.record(
            UUID.randomUUID().toString(), saved.tenantId(), IdentityAuditAction.SERVICE_IDENTITY_DISABLED, null,
            saved.serviceIdentityId(), null, AuditOutcome.SUCCESS, null, new CorrelationId(command.correlationId()), clock.now()
        ));
        return saved;
    }

    @Override
    public ServiceIdentity findById(String serviceIdentityId) {
        return findByIdOrThrow(serviceIdentityId);
    }

    private ServiceIdentity findByIdOrThrow(String serviceIdentityId) {
        return serviceIdentityRepository.findById(serviceIdentityId)
            .orElseThrow(() -> new ServiceIdentityNotFoundException(serviceIdentityId));
    }
}
