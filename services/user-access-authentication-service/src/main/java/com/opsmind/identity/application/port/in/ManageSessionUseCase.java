package com.opsmind.identity.application.port.in;

import com.opsmind.identity.application.command.RevokeSessionCommand;
import com.opsmind.identity.application.command.StartSessionCommand;
import com.opsmind.identity.domain.session.UserSession;

/** 13-package-and-class-design §Primary Input Ports; 04-use-cases §OIDC login, §Logout/revocation. */
public interface ManageSessionUseCase {

    UserSession start(StartSessionCommand command);

    UserSession revoke(RevokeSessionCommand command);

    UserSession findById(String userSessionId);
}
