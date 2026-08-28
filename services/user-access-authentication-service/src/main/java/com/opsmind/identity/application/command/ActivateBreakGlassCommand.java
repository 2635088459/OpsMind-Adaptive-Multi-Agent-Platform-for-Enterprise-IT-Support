package com.opsmind.identity.application.command;

import com.opsmind.identity.domain.role.ResourceScope;

import java.time.Duration;
import java.util.List;

/**
 * SPEC-UA-019 (Break Glass And Account Recovery). {@code issuer}/{@code
 * subject} are the activating admin's own verified JWT identity — a
 * break-glass grant is always self-activated by the strongly-authenticated
 * admin who will hold it, never granted to a third party by someone else
 * (04-use-cases §Break-glass: "Authorized admin | Strong authentication +
 * ..."). {@code approvalReference} is domain 06's own already-decided
 * approval fact, asserted by the trusted caller — never independently
 * validated here (02-business-invariants #8). {@code
 * requiredAssuranceLevel}/{@code requiredAssuranceMethods} are the strong-
 * authentication threshold the CALLER decides applies (domain 01 stays
 * policy-agnostic, exactly like SPEC-UA-016's own requiredAssurance),
 * checked against the activating admin's own current {@code sessionId} via
 * {@code AuthenticationAssurance#satisfies}.
 */
public record ActivateBreakGlassCommand(
    String tenantId,
    String issuer,
    String subject,
    String sessionId,
    ResourceScope scope,
    String approvalReference,
    String reason,
    String requiredAssuranceLevel,
    List<String> requiredAssuranceMethods,
    Duration ttl,
    String correlationId
) {
}
