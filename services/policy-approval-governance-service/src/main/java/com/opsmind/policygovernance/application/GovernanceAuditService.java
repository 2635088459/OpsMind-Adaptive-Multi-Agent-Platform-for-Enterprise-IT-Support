package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.application.port.AuditIntegrityPort;
import com.opsmind.policygovernance.application.port.GovernanceAuditRepository;
import com.opsmind.policygovernance.domain.audit.GovernanceAuditRecord;
import com.opsmind.policygovernance.domain.shared.DomainEvent;
import com.opsmind.policygovernance.domain.shared.SimpleGovernanceEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Writes governance audit facts (INV-PG-008: every governance action must
 * be audited). Per the SPEC-PG-001 domain rule "every governance state
 * transition must write audit/outbox in the same transaction", {@link
 * #record} both appends the audit record and stages the corresponding
 * governance event in one call, so both happen together once SPEC-PG-002
 * adds a real {@code @Transactional} boundary around this method.
 */
@Service
public class GovernanceAuditService {

    private final GovernanceAuditRepository auditRepository;
    private final AuditIntegrityPort auditIntegrityPort;
    private final OutboxDispatchService outboxDispatchService;
    private final Clock clock;

    public GovernanceAuditService(
        GovernanceAuditRepository auditRepository,
        AuditIntegrityPort auditIntegrityPort,
        OutboxDispatchService outboxDispatchService,
        Clock clock
    ) {
        this.auditRepository = auditRepository;
        this.auditIntegrityPort = auditIntegrityPort;
        this.outboxDispatchService = outboxDispatchService;
        this.clock = clock;
    }

    /** Auto-generates a placeholder event to stage — see {@link #record(GovernanceAuditRecord.Action, String, String, String, String, String, String, String, String, DomainEvent)} for actions with a real, versioned event contract. */
    @Transactional
    public GovernanceAuditRecord record(
        GovernanceAuditRecord.Action action,
        String actorId,
        String sourceDomain,
        String sourceRequestId,
        String policyId,
        String policyVersion,
        String reason,
        String correlationId,
        String causationId
    ) {
        return record(
            action, actorId, sourceDomain, sourceRequestId, policyId, policyVersion, reason, correlationId, causationId,
            SimpleGovernanceEvent.of("governance.audit." + action.name().toLowerCase() + ".v1", correlationId, causationId)
        );
    }

    /**
     * SPEC-PG-010: lets a caller stage the real, versioned {@link
     * DomainEvent} 06-event-contracts names for this action (e.g. {@code
     * approval.requested.v1}) instead of the generic placeholder the other
     * overload auto-generates — still in the same transaction as the audit
     * write (SPEC-PG-001 domain rule).
     */
    @Transactional
    public GovernanceAuditRecord record(
        GovernanceAuditRecord.Action action,
        String actorId,
        String sourceDomain,
        String sourceRequestId,
        String policyId,
        String policyVersion,
        String reason,
        String correlationId,
        String causationId,
        DomainEvent eventToPublish
    ) {
        GovernanceAuditRecord unsealed = new GovernanceAuditRecord(
            UUID.randomUUID().toString(), action, actorId, sourceDomain, sourceRequestId,
            policyId, policyVersion, reason, correlationId, causationId, null, clock.instant()
        );
        String integrityHash = auditIntegrityPort.computeIntegrityHash(unsealed);
        GovernanceAuditRecord saved = auditRepository.append(unsealed.withIntegrityHash(integrityHash));
        outboxDispatchService.stage(eventToPublish);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<GovernanceAuditRecord> findByCorrelationId(String correlationId) {
        return auditRepository.findByCorrelationId(correlationId);
    }
}
