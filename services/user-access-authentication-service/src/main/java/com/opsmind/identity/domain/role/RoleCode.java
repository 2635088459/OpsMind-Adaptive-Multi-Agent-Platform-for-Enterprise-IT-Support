package com.opsmind.identity.domain.role;

/** 01-domain-model §RoleAssignment. The full role/permission model is SPEC-UA-011's job; this is the stable vocabulary it builds on. */
public enum RoleCode {
    EMPLOYEE,
    SUPPORT_AGENT,
    APPROVER,
    IT_ADMIN,
    PLATFORM_ADMIN,
    AUDITOR
}
