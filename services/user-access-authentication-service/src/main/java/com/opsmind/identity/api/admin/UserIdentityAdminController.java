package com.opsmind.identity.api.admin;

import com.opsmind.identity.api.IdentityRequestContext;
import com.opsmind.identity.application.command.ChangeUserIdentityStatusCommand;
import com.opsmind.identity.application.dto.ChangeUserIdentityStatusRequest;
import com.opsmind.identity.application.dto.UserIdentityView;
import com.opsmind.identity.application.port.in.ProvisionUserUseCase;
import com.opsmind.identity.domain.user.UserIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 05-api-contracts {@code PUT /users/{id}/status} — {@code identity:user:admin}. */
@RestController
public class UserIdentityAdminController {

    private final ProvisionUserUseCase provisionUserUseCase;

    public UserIdentityAdminController(ProvisionUserUseCase provisionUserUseCase) {
        this.provisionUserUseCase = provisionUserUseCase;
    }

    @PutMapping("/internal/identity/v1/users/{userIdentityId}/status")
    public ResponseEntity<UserIdentityView> changeStatus(
        @PathVariable String userIdentityId, @Valid @RequestBody ChangeUserIdentityStatusRequest request, HttpServletRequest httpRequest
    ) {
        ChangeUserIdentityStatusCommand command = new ChangeUserIdentityStatusCommand(
            userIdentityId, request.status(), request.reason(), IdentityRequestContext.correlationId(httpRequest)
        );
        UserIdentity updated = provisionUserUseCase.changeStatus(command);
        return ResponseEntity.ok(UserIdentityView.from(updated));
    }
}
