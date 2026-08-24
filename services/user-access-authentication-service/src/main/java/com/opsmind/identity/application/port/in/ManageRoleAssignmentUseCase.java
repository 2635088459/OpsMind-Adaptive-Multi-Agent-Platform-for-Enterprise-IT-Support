package com.opsmind.identity.application.port.in;

import com.opsmind.identity.application.command.GrantRoleAssignmentCommand;
import com.opsmind.identity.application.command.RevokeRoleAssignmentCommand;
import com.opsmind.identity.application.query.ListRoleAssignmentsQuery;
import com.opsmind.identity.domain.role.RoleAssignment;

import java.util.List;

/** 13-package-and-class-design §Primary Input Ports; 04-use-cases §Grant/revoke role. */
public interface ManageRoleAssignmentUseCase {

    RoleAssignment grant(GrantRoleAssignmentCommand command);

    RoleAssignment revoke(RevokeRoleAssignmentCommand command);

    List<RoleAssignment> listForUser(ListRoleAssignmentsQuery query);
}
