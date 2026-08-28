package com.opsmind.identity.support;

import com.opsmind.identity.application.port.out.BreakGlassGrantRepository;
import com.opsmind.identity.domain.breakglass.BreakGlassGrant;
import com.opsmind.identity.domain.breakglass.BreakGlassStatus;
import com.opsmind.identity.domain.user.ExternalSubject;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Fast, dependency-free application-service unit-test double for {@link BreakGlassGrantRepository}. Real persistence is {@code BreakGlassGrantPersistenceAdapter} (SPEC-UA-019). */
public class InMemoryBreakGlassGrantRepository implements BreakGlassGrantRepository {

    private final Map<String, BreakGlassGrant> byId = new ConcurrentHashMap<>();

    @Override
    public Optional<BreakGlassGrant> findById(String breakGlassGrantId) {
        return Optional.ofNullable(byId.get(breakGlassGrantId));
    }

    @Override
    public List<BreakGlassGrant> findByExternalSubject(String tenantId, ExternalSubject externalSubject) {
        return byId.values().stream()
            .filter(g -> g.tenantId().value().equals(tenantId) && g.externalSubject().equals(externalSubject))
            .toList();
    }

    @Override
    public List<BreakGlassGrant> findActiveExpired(Instant now) {
        return byId.values().stream()
            .filter(g -> g.status() == BreakGlassStatus.ACTIVE && !now.isBefore(g.expiresAt()))
            .toList();
    }

    @Override
    public List<BreakGlassGrant> findByApprovalReference(String approvalReference) {
        return byId.values().stream()
            .filter(g -> g.approvalReference().equals(approvalReference))
            .sorted(java.util.Comparator.comparing(BreakGlassGrant::createdAt))
            .toList();
    }

    @Override
    public BreakGlassGrant save(BreakGlassGrant grant) {
        byId.put(grant.breakGlassGrantId(), grant);
        return grant;
    }
}
