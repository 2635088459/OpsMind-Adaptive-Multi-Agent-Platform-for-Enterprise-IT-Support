package com.opsmind.identity.application.exception;

public class RoleAssignmentNotFoundException extends RuntimeException {

    public RoleAssignmentNotFoundException(String roleAssignmentId) {
        super("role assignment " + roleAssignmentId + " was not found");
    }
}
