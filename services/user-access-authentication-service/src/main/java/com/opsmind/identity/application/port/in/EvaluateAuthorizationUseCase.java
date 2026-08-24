package com.opsmind.identity.application.port.in;

import com.opsmind.identity.application.command.EvaluateAuthorizationCommand;
import com.opsmind.identity.domain.decision.AuthorizationDecision;

/** 13-package-and-class-design §Primary Input Ports; 04-use-cases §Authorization evaluation. */
public interface EvaluateAuthorizationUseCase {

    AuthorizationDecision evaluate(EvaluateAuthorizationCommand command);

    AuthorizationDecision findById(String decisionId);
}
