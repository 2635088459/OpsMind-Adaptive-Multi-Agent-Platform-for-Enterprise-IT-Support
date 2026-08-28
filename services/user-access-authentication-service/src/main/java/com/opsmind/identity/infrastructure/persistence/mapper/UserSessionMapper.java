package com.opsmind.identity.infrastructure.persistence.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.opsmind.identity.domain.session.AuthenticationAssurance;
import com.opsmind.identity.domain.session.SessionStatus;
import com.opsmind.identity.domain.session.UserSession;
import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.domain.user.ExternalSubject;
import com.opsmind.identity.infrastructure.persistence.jpa.entity.UserSessionJpaEntity;

import java.util.List;

public final class UserSessionMapper {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private UserSessionMapper() {
    }

    public static UserSessionJpaEntity toEntity(UserSession session) {
        return new UserSessionJpaEntity(
            session.userSessionId(), session.tenantId().value(), session.externalSubject().issuer(), session.externalSubject().subject(),
            session.idpSessionIdHash(), session.tokenIdHash(), session.clientId(), session.assurance().acr(),
            JsonSupport.writeList(session.assurance().amr()), session.assurance().authTime(), session.deviceIdHash(),
            session.status().name(), session.startedAt(), session.lastSeenAt(), session.expiresAt(), session.revokedBy(),
            session.revokedAt(), session.revocationReason(), session.endSessionNotifiedAt(), session.createdAt(), session.updatedAt(),
            PersistenceVersion.entityVersion(session.version()), session.version() == 0
        );
    }

    public static UserSession toDomain(UserSessionJpaEntity entity) {
        AuthenticationAssurance assurance = new AuthenticationAssurance(entity.getAcr(), JsonSupport.readList(entity.getAmrJson(), STRING_LIST), entity.getAuthTime());
        return UserSession.reconstruct(
            entity.getUserSessionId(), new TenantId(entity.getTenantId()), new ExternalSubject(entity.getIssuer(), entity.getSubject()),
            entity.getIdpSessionIdHash(), entity.getTokenIdHash(), entity.getClientId(), assurance, entity.getDeviceIdHash(),
            SessionStatus.valueOf(entity.getStatus()), entity.getStartedAt(), entity.getLastSeenAt(), entity.getExpiresAt(),
            entity.getRevokedBy(), entity.getRevokedAt(), entity.getRevocationReason(), entity.getEndSessionNotifiedAt(),
            entity.getCreatedAt(), entity.getUpdatedAt(), entity.getVersion()
        );
    }
}
