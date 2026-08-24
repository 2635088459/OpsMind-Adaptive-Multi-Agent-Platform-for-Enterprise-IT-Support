package com.opsmind.identity.application.query;

import com.opsmind.identity.domain.user.ExternalSubject;

public record FindUserIdentityByExternalSubjectQuery(String tenantId, ExternalSubject externalSubject) {
}
