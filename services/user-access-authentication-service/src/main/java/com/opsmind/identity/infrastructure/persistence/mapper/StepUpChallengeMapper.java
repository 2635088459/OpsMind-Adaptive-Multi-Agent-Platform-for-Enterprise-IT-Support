package com.opsmind.identity.infrastructure.persistence.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.domain.stepup.AuthorizationTarget;
import com.opsmind.identity.domain.stepup.StepUpChallenge;
import com.opsmind.identity.domain.stepup.StepUpStatus;
import com.opsmind.identity.domain.user.ExternalSubject;
import com.opsmind.identity.infrastructure.persistence.jpa.entity.StepUpChallengeJpaEntity;

import java.util.List;

public final class StepUpChallengeMapper {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private StepUpChallengeMapper() {
    }

    public static StepUpChallengeJpaEntity toEntity(StepUpChallenge challenge) {
        return new StepUpChallengeJpaEntity(
            challenge.stepUpChallengeId(), challenge.challengeKey(), challenge.tenantId().value(), challenge.externalSubject().issuer(),
            challenge.externalSubject().subject(), challenge.userSessionId(), challenge.target().action(), challenge.target().resourceType(),
            challenge.target().resourceId(), challenge.requiredAssuranceLevel(), JsonSupport.writeList(challenge.requiredMethods()),
            challenge.nonceHash(), challenge.status().name(), challenge.attemptCount(), challenge.maxAttempts(), challenge.createdAt(),
            challenge.expiresAt(), challenge.verifiedAt(), challenge.proofIdHash(), challenge.consumedAt(), challenge.correlationId(),
            PersistenceVersion.entityVersion(challenge.version()), challenge.version() == 0
        );
    }

    public static StepUpChallenge toDomain(StepUpChallengeJpaEntity entity) {
        return StepUpChallenge.reconstruct(
            entity.getStepUpChallengeId(), entity.getChallengeKey(), new TenantId(entity.getTenantId()),
            new ExternalSubject(entity.getIssuer(), entity.getSubject()), entity.getUserSessionId(),
            new AuthorizationTarget(entity.getAction(), entity.getResourceType(), entity.getResourceId()),
            entity.getRequiredAssuranceLevel(), JsonSupport.readList(entity.getRequiredMethodsJson(), STRING_LIST), entity.getNonceHash(),
            StepUpStatus.valueOf(entity.getStatus()), entity.getAttemptCount(), entity.getMaxAttempts(), entity.getCreatedAt(),
            entity.getExpiresAt(), entity.getVerifiedAt(), entity.getProofIdHash(), entity.getConsumedAt(), entity.getCorrelationId(),
            entity.getVersion()
        );
    }
}
