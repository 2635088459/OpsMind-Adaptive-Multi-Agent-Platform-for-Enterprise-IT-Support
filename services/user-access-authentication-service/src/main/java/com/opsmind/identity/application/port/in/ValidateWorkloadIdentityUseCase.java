package com.opsmind.identity.application.port.in;

import com.opsmind.identity.application.command.ValidateWorkloadIdentityCommand;
import com.opsmind.identity.application.dto.WorkloadIdentityView;

/** 11-security §Tokens and protocols; 04-use-cases §Workload identity. */
public interface ValidateWorkloadIdentityUseCase {

    /** @throws com.opsmind.identity.application.exception.WorkloadIdentityNotTrustedException when the caller cannot be trusted as a registered, in-window workload with a matching audience/scope. */
    WorkloadIdentityView validate(ValidateWorkloadIdentityCommand command);
}
