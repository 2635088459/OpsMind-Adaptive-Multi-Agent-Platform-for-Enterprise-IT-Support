package com.opsmind.identity.application.command;

import com.opsmind.identity.domain.role.ResourceScope;
import com.opsmind.identity.domain.role.RoleCode;

import java.util.List;

/**
 * 05-api-contracts {@code POST /authorization-decisions}. {@code
 * requiredRole}/{@code requiredScope} are nullable: when absent, evaluation
 * only checks the subject's {@code UserIdentity} is trusted and {@code
 * ACTIVE} (02-business-invariants #5's "trusted principal" leg) — the full
 * role/scope intersection algorithm is SPEC-UA-014's job.
 *
 * <p>{@code resourceOwnerId} is 05-api-contracts' own {@code
 * ownershipContext} field (SPEC-UA-015, Self Service And Resource
 * Ownership): the trusted caller's own assertion of who owns the target
 * resource — domain 01 has no cross-domain knowledge letting it resolve
 * resource ownership itself (a ticket's owner is domain 02's own fact, not
 * this domain's), so it can only compare the caller's assertion against the
 * verified {@code subjectId}, never derive ownership independently. Ignored
 * unless {@code requiredScope}'s type is {@code SELF} — every other scope
 * is an organizational (not ownership) concern, already fully handled by
 * SPEC-UA-014's own {@link ResourceScope#covers}.
 *
 * <p>{@code requiredAssuranceLevel}/{@code requiredAssuranceMethods} are
 * 05-api-contracts' own {@code requiredAssurance} field (SPEC-UA-016,
 * Authentication Context And Assurance Level — {@code
 * AuthenticationAssurance}'s own javadoc: "full assurance-level computation
 * is SPEC-UA-016's job"). Both nullable/empty: absent means no assurance
 * requirement at all. When present, {@code sessionId} must resolve to the
 * subject's own currently-active {@code UserSession} whose own {@code
 * AuthenticationAssurance} is compared against them — insufficient
 * assurance is {@code REQUIRE_STEP_UP}, not {@code DENY}: the caller already
 * has a legitimate path forward (step up the very session it already holds),
 * unlike an outright role/scope/ownership failure.
 */
public record EvaluateAuthorizationCommand(
    String tenantId,
    String actorId,
    String subjectId,
    String sessionId,
    String action,
    String resourceType,
    String resourceId,
    RoleCode requiredRole,
    ResourceScope requiredScope,
    String resourceOwnerId,
    String requiredAssuranceLevel,
    List<String> requiredAssuranceMethods,
    String correlationId
) {
}
