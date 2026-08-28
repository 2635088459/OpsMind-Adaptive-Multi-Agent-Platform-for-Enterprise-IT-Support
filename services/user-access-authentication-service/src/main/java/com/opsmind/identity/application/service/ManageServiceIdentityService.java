package com.opsmind.identity.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsmind.identity.application.command.DisableServiceIdentityCommand;
import com.opsmind.identity.application.command.RegisterServiceIdentityCommand;
import com.opsmind.identity.application.exception.ServiceIdentityNotFoundException;
import com.opsmind.identity.application.port.in.ManageServiceIdentityUseCase;
import com.opsmind.identity.application.port.out.AuditPort;
import com.opsmind.identity.application.port.out.ClockPort;
import com.opsmind.identity.application.port.out.EventPublisherPort;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * UC-UA-008: register/disable a {@link ServiceIdentity} and reconcile its
 * time-driven retirement (03-state-machine §ServiceIdentity). Real
 * client-credentials/mTLS validation is SPEC-UA-010's job.
 *
 * <p>08-transaction-and-outbox: "disable service identity: service identity
 * + audit + event" — every transition commits state, audit, and an outbox
 * row together (SPEC-UA-003).
 */
@Service
public class ManageServiceIdentityService implements ManageServiceIdentityUseCase {

    private static final String AGGREGATE_TYPE = "ServiceIdentity";

    private final ServiceIdentityRepository serviceIdentityRepository;
    private final AuditPort auditPort;
    private final EventPublisherPort eventPublisherPort;
    private final ClockPort clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ManageServiceIdentityService(
        ServiceIdentityRepository serviceIdentityRepository, AuditPort auditPort, EventPublisherPort eventPublisherPort, ClockPort clock
    ) {
        this.serviceIdentityRepository = serviceIdentityRepository;
        this.auditPort = auditPort;
        this.eventPublisherPort = eventPublisherPort;
        this.clock = clock;
    }

    @Override
    @Transactional
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
        audit(saved, IdentityAuditAction.SERVICE_IDENTITY_REGISTERED, AuditOutcome.SUCCESS, "service=" + saved.serviceName(), command.correlationId());
        return saved;
    }

    @Override
    @Transactional
    public ServiceIdentity disable(DisableServiceIdentityCommand command) {
        ServiceIdentity current = findByIdOrThrow(command.serviceIdentityId());
        ServiceIdentity saved = serviceIdentityRepository.save(current.disable(clock.now()));
        audit(saved, IdentityAuditAction.SERVICE_IDENTITY_DISABLED, AuditOutcome.SUCCESS, null, command.correlationId());
        publish(saved, "identity.service.disabled.v1", command.correlationId());
        return saved;
    }

    @Override
    public ServiceIdentity findById(String serviceIdentityId) {
        return findByIdOrThrow(serviceIdentityId);
    }

    /** 03-state-machine §ServiceIdentity: reconciliation retires an {@code ACTIVE} identity directly once past its own {@code validUntil} — admin/scheduler-triggered. */
    @Override
    @Transactional
    public int reconcileRetired() {
        Instant now = clock.now();
        int count = 0;
        for (ServiceIdentity active : serviceIdentityRepository.findActiveExpired(now)) {
            ServiceIdentity saved = serviceIdentityRepository.save(active.retire(now));
            audit(saved, IdentityAuditAction.SERVICE_IDENTITY_RETIRED, AuditOutcome.SUCCESS, "validUntil reached", UUID.randomUUID().toString());
            count++;
        }
        return count;
    }

    private ServiceIdentity findByIdOrThrow(String serviceIdentityId) {
        return serviceIdentityRepository.findById(serviceIdentityId)
            .orElseThrow(() -> new ServiceIdentityNotFoundException(serviceIdentityId));
    }

    private void audit(ServiceIdentity saved, IdentityAuditAction action, AuditOutcome outcome, String reason, String correlationId) {
        auditPort.record(IdentityAuditRecord.record(
            UUID.randomUUID().toString(), saved.tenantId(), action, null, saved.serviceIdentityId(), null, outcome, reason,
            new CorrelationId(correlationId), clock.now()
        ));
    }

    private void publish(ServiceIdentity saved, String eventType, String correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("serviceIdentityId", saved.serviceIdentityId());
        payload.put("serviceName", saved.serviceName());
        payload.put("disabledAt", saved.disabledAt() == null ? null : saved.disabledAt().toString());
        try {
            eventPublisherPort.publish(eventType, AGGREGATE_TYPE, saved.serviceIdentityId(), objectMapper.writeValueAsString(payload), correlationId);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize service identity event payload", e);
        }
    }
}
