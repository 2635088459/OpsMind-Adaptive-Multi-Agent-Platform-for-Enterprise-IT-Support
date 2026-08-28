package com.opsmind.identity.application.service;

import com.opsmind.identity.application.dto.PrincipalContextView;
import com.opsmind.identity.application.port.in.IntrospectPrincipalUseCase;
import com.opsmind.identity.application.port.out.UserIdentityRepository;
import com.opsmind.identity.application.port.out.UserSessionRepository;
import com.opsmind.identity.application.query.IntrospectPrincipalContextQuery;
import com.opsmind.identity.domain.session.UserSession;
import com.opsmind.identity.domain.user.ExternalSubject;
import com.opsmind.identity.domain.user.UserIdentity;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * UC-UA-005 (SPEC-UA-007, Principal Claims Normalization): assembles the
 * normalized principal/assurance/session-status view 05-api-contracts'
 * {@code POST /tokens/introspect-context} row names. Read-only and
 * side-effect-free by design — unlike {@code ProvisionUserUseCase#link}
 * (called from {@code GET /users/me}), this never auto-provisions a
 * {@link UserIdentity}; a not-yet-linked subject simply comes back with
 * null identity fields rather than being created as a side effect of an
 * introspection call.
 *
 * <p>{@code userSessionId}, if supplied, is only ever honored when the
 * session it names actually belongs to this same verified {@code
 * (tenantId, issuer, subject)} (02-business-invariants: "SELF permits only
 * resources mapped to the token subject") — otherwise it is treated
 * exactly like "not found," never disclosing whether some other subject's
 * session id exists (05-api-contracts: "Errors do not distinguish
 * nonexistent users from unauthorized visibility").
 */
@Service
public class IntrospectPrincipalService implements IntrospectPrincipalUseCase {

    private final UserIdentityRepository userIdentityRepository;
    private final UserSessionRepository userSessionRepository;

    public IntrospectPrincipalService(UserIdentityRepository userIdentityRepository, UserSessionRepository userSessionRepository) {
        this.userIdentityRepository = userIdentityRepository;
        this.userSessionRepository = userSessionRepository;
    }

    @Override
    public PrincipalContextView introspect(IntrospectPrincipalContextQuery query) {
        ExternalSubject externalSubject = new ExternalSubject(query.issuer(), query.subject());
        Optional<UserIdentity> identity = userIdentityRepository.findByExternalSubject(query.tenantId(), externalSubject);

        String resolvedSessionId = null;
        String sessionStatus = null;
        if (query.userSessionId() != null && !query.userSessionId().isBlank()) {
            Optional<UserSession> session = userSessionRepository.findById(query.userSessionId())
                .filter(s -> s.tenantId().value().equals(query.tenantId()) && s.externalSubject().equals(externalSubject));
            if (session.isPresent()) {
                resolvedSessionId = session.get().userSessionId();
                sessionStatus = session.get().status().name();
            }
        }

        return new PrincipalContextView(
            query.tenantId(), query.issuer(), query.subject(),
            identity.map(UserIdentity::userIdentityId).orElse(null),
            identity.map(u -> u.status().name()).orElse(null),
            query.acr(), query.amr(), query.authTime(),
            resolvedSessionId, sessionStatus
        );
    }
}
