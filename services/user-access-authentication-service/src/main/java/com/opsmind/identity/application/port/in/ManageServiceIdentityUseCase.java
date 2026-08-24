package com.opsmind.identity.application.port.in;

import com.opsmind.identity.application.command.DisableServiceIdentityCommand;
import com.opsmind.identity.application.command.RegisterServiceIdentityCommand;
import com.opsmind.identity.domain.workload.ServiceIdentity;

/** 13-package-and-class-design §Primary Input Ports; 04-use-cases §Workload identity. */
public interface ManageServiceIdentityUseCase {

    ServiceIdentity register(RegisterServiceIdentityCommand command);

    ServiceIdentity disable(DisableServiceIdentityCommand command);

    ServiceIdentity findById(String serviceIdentityId);
}
