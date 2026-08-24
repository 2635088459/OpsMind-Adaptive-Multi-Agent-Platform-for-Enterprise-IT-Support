package com.opsmind.identity.application.port.in;

import com.opsmind.identity.application.command.ChangeUserIdentityStatusCommand;
import com.opsmind.identity.application.command.LinkUserIdentityCommand;
import com.opsmind.identity.application.command.SyncUserIdentityCommand;
import com.opsmind.identity.application.query.FindUserIdentityByExternalSubjectQuery;
import com.opsmind.identity.domain.user.UserIdentity;

/** 13-package-and-class-design §Primary Input Ports; 04-use-cases §OIDC login, §User synchronization. */
public interface ProvisionUserUseCase {

    UserIdentity link(LinkUserIdentityCommand command);

    UserIdentity sync(SyncUserIdentityCommand command);

    UserIdentity changeStatus(ChangeUserIdentityStatusCommand command);

    UserIdentity findById(String userIdentityId);

    UserIdentity findByExternalSubject(FindUserIdentityByExternalSubjectQuery query);
}
