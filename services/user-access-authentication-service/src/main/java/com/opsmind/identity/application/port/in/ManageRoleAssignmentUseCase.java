package com.opsmind.identity.application.port.in;

import com.opsmind.identity.application.command.CancelRoleAssignmentCommand;
import com.opsmind.identity.application.command.GrantRoleAssignmentCommand;
import com.opsmind.identity.application.command.RevokeRoleAssignmentCommand;
import com.opsmind.identity.application.dto.RoleAssignmentReconciliationResult;
import com.opsmind.identity.application.query.ListRoleAssignmentsQuery;
import com.opsmind.identity.domain.role.RoleAssignment;

import java.util.List;

/** 13-package-and-class-design §Primary Input Ports; 04-use-cases §Grant/revoke role. */
public interface ManageRoleAssignmentUseCase {

    RoleAssignment grant(GrantRoleAssignmentCommand command);

    RoleAssignment revoke(RevokeRoleAssignmentCommand command);

    /** 03-state-machine §RoleAssignment: {@code PENDING --cancel--> CANCELLED}. */
    RoleAssignment cancel(CancelRoleAssignmentCommand command);

    List<RoleAssignment> listForUser(ListRoleAssignmentsQuery query);

    /** SPEC-UA-007 (05-api-contracts {@code GET /users/me}: "Minimum profile plus effective roles/scopes") — only {@code ACTIVE} assignments currently within their validity window, unlike {@link #listForUser} which returns every assignment regardless of status. */
    List<RoleAssignment> listEffectiveForUser(ListRoleAssignmentsQuery query);

    /**
     * The time-driven edges 03-state-machine names ({@code PENDING
     * --activate(validFrom)--> ACTIVE}, {@code ACTIVE --validUntil
     * reached--> EXPIRED}) — admin/scheduler-triggered, mirrors {@code
     * OutboxDispatchService#publishPending}'s own "nothing calls this
     * automatically" convention.
     */
    RoleAssignmentReconciliationResult reconcileDueTransitions();
}
