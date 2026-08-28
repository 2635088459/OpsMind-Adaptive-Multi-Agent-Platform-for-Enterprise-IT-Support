package com.opsmind.identity.infrastructure.persistence.adapter;

import com.opsmind.identity.application.port.out.RoleAssignmentRepository;
import com.opsmind.identity.domain.role.ResourceScope;
import com.opsmind.identity.domain.role.RoleAssignment;
import com.opsmind.identity.domain.role.RoleCode;
import com.opsmind.identity.infrastructure.persistence.jpa.repository.SpringDataRoleAssignmentJpaRepository;
import com.opsmind.identity.infrastructure.persistence.mapper.RoleAssignmentMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * SPEC-UA-002/SPEC-UA-012. Replaces the SPEC-UA-001-scoped {@code
 * InMemoryRoleAssignmentRepository}. Overlap prevention itself is the real
 * database constraint {@code uq_role_assignments_active} (migration V003)
 * — this adapter's {@link #findActive} only makes granting idempotent, it
 * is not what prevents a duplicate ACTIVE row.
 */
@Component
public class RoleAssignmentPersistenceAdapter implements RoleAssignmentRepository {

    private final SpringDataRoleAssignmentJpaRepository repository;

    public RoleAssignmentPersistenceAdapter(SpringDataRoleAssignmentJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<RoleAssignment> findById(String roleAssignmentId) {
        return repository.findById(roleAssignmentId).map(RoleAssignmentMapper::toDomain);
    }

    @Override
    public List<RoleAssignment> findByUserIdentityId(String userIdentityId) {
        return repository.findByUserIdentityId(userIdentityId).stream().map(RoleAssignmentMapper::toDomain).toList();
    }

    @Override
    public Optional<RoleAssignment> findActive(String userIdentityId, RoleCode roleCode, ResourceScope scope, Instant now) {
        return repository.findActive(userIdentityId, roleCode.name(), scope.scopeType().name(), scope.scopeId(), now)
            .stream().findFirst().map(RoleAssignmentMapper::toDomain);
    }

    @Override
    public List<RoleAssignment> findPendingDue(Instant now) {
        return repository.findByStatusAndValidFromLessThanEqual("PENDING", now).stream().map(RoleAssignmentMapper::toDomain).toList();
    }

    @Override
    public List<RoleAssignment> findActiveExpired(Instant now) {
        return repository.findByStatusAndValidUntilLessThanEqual("ACTIVE", now).stream().map(RoleAssignmentMapper::toDomain).toList();
    }

    @Override
    public RoleAssignment save(RoleAssignment roleAssignment) {
        repository.save(RoleAssignmentMapper.toEntity(roleAssignment));
        return roleAssignment;
    }
}
