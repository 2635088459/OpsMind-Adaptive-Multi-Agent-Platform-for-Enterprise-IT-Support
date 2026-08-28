package com.opsmind.identity.support;

import com.opsmind.identity.application.port.out.RoleAssignmentRepository;
import com.opsmind.identity.domain.role.ResourceScope;
import com.opsmind.identity.domain.role.RoleAssignment;
import com.opsmind.identity.domain.role.RoleAssignmentStatus;
import com.opsmind.identity.domain.role.RoleCode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Fast, dependency-free application-service unit-test double for {@link RoleAssignmentRepository}. Real persistence is {@code RoleAssignmentPersistenceAdapter} (SPEC-UA-002/012). */
public class InMemoryRoleAssignmentRepository implements RoleAssignmentRepository {

    private final Map<String, RoleAssignment> byId = new ConcurrentHashMap<>();

    @Override
    public Optional<RoleAssignment> findById(String roleAssignmentId) {
        return Optional.ofNullable(byId.get(roleAssignmentId));
    }

    @Override
    public List<RoleAssignment> findByUserIdentityId(String userIdentityId) {
        return byId.values().stream().filter(a -> a.userIdentityId().equals(userIdentityId)).toList();
    }

    @Override
    public Optional<RoleAssignment> findActive(String userIdentityId, RoleCode roleCode, ResourceScope scope, Instant now) {
        return byId.values().stream()
            .filter(a -> a.userIdentityId().equals(userIdentityId) && a.matches(roleCode, scope, now))
            .findFirst();
    }

    @Override
    public List<RoleAssignment> findPendingDue(Instant now) {
        return byId.values().stream()
            .filter(a -> a.status() == RoleAssignmentStatus.PENDING && !now.isBefore(a.validFrom()))
            .toList();
    }

    @Override
    public List<RoleAssignment> findActiveExpired(Instant now) {
        return byId.values().stream()
            .filter(a -> a.status() == RoleAssignmentStatus.ACTIVE && a.validUntil() != null && !now.isBefore(a.validUntil()))
            .toList();
    }

    @Override
    public RoleAssignment save(RoleAssignment roleAssignment) {
        byId.put(roleAssignment.roleAssignmentId(), roleAssignment);
        return roleAssignment;
    }
}
