package com.opsmind.identity.infrastructure.persistence.adapter;

import com.opsmind.identity.application.port.out.RoleAssignmentRepository;
import com.opsmind.identity.domain.role.ResourceScope;
import com.opsmind.identity.domain.role.RoleAssignment;
import com.opsmind.identity.domain.role.RoleCode;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** SPEC-UA-001-scoped placeholder — see {@link InMemoryUserIdentityRepository}'s own javadoc for the deferral this mirrors. */
@Repository
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
    public RoleAssignment save(RoleAssignment roleAssignment) {
        byId.put(roleAssignment.roleAssignmentId(), roleAssignment);
        return roleAssignment;
    }
}
