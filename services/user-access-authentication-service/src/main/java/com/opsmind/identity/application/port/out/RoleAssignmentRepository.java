package com.opsmind.identity.application.port.out;

import com.opsmind.identity.domain.role.ResourceScope;
import com.opsmind.identity.domain.role.RoleAssignment;
import com.opsmind.identity.domain.role.RoleCode;

import java.util.List;
import java.util.Optional;

public interface RoleAssignmentRepository {

    Optional<RoleAssignment> findById(String roleAssignmentId);

    List<RoleAssignment> findByUserIdentityId(String userIdentityId);

    /** Used to make granting an already-active role idempotent (acceptance criteria: no repeated side effects). */
    Optional<RoleAssignment> findActive(String userIdentityId, RoleCode roleCode, ResourceScope scope, java.time.Instant now);

    RoleAssignment save(RoleAssignment roleAssignment);
}
