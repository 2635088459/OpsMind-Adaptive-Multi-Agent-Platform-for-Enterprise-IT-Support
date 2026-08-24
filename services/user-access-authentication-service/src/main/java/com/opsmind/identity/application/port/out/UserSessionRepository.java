package com.opsmind.identity.application.port.out;

import com.opsmind.identity.domain.session.UserSession;

import java.util.Optional;

public interface UserSessionRepository {

    Optional<UserSession> findById(String userSessionId);

    UserSession save(UserSession session);
}
