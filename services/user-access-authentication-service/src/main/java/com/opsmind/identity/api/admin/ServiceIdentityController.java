package com.opsmind.identity.api.admin;

import com.opsmind.identity.api.IdentityRequestContext;
import com.opsmind.identity.application.command.DisableServiceIdentityCommand;
import com.opsmind.identity.application.command.RegisterServiceIdentityCommand;
import com.opsmind.identity.application.dto.RegisterServiceIdentityRequest;
import com.opsmind.identity.application.dto.ServiceIdentityView;
import com.opsmind.identity.application.port.in.ManageServiceIdentityUseCase;
import com.opsmind.identity.domain.workload.ServiceIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 04-use-cases §Workload identity — administrative registration/disablement of a {@code ServiceIdentity}. */
@RestController
public class ServiceIdentityController {

    private final ManageServiceIdentityUseCase manageServiceIdentityUseCase;

    public ServiceIdentityController(ManageServiceIdentityUseCase manageServiceIdentityUseCase) {
        this.manageServiceIdentityUseCase = manageServiceIdentityUseCase;
    }

    @PostMapping("/internal/identity/v1/service-identities")
    public ResponseEntity<ServiceIdentityView> register(
        @Valid @RequestBody RegisterServiceIdentityRequest request, Authentication authentication, HttpServletRequest httpRequest
    ) {
        IdentityRequestContext.VerifiedIssuerAndSubject verified = IdentityRequestContext.verifiedIssuerAndSubject(authentication);
        RegisterServiceIdentityCommand command = new RegisterServiceIdentityCommand(
            request.tenantId(), verified.issuer(), verified.subject(), request.clientId(), request.serviceName(),
            request.allowedAudiences(), request.allowedScopes(), request.validFrom(), request.validUntil(),
            IdentityRequestContext.correlationId(httpRequest)
        );
        ServiceIdentity saved = manageServiceIdentityUseCase.register(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(ServiceIdentityView.from(saved));
    }

    @PostMapping("/internal/identity/v1/service-identities/{serviceIdentityId}/disable")
    public ResponseEntity<ServiceIdentityView> disable(@PathVariable String serviceIdentityId, HttpServletRequest httpRequest) {
        DisableServiceIdentityCommand command = new DisableServiceIdentityCommand(serviceIdentityId, IdentityRequestContext.correlationId(httpRequest));
        return ResponseEntity.ok(ServiceIdentityView.from(manageServiceIdentityUseCase.disable(command)));
    }

    @GetMapping("/internal/identity/v1/service-identities/{serviceIdentityId}")
    public ResponseEntity<ServiceIdentityView> findById(@PathVariable String serviceIdentityId) {
        return ResponseEntity.ok(ServiceIdentityView.from(manageServiceIdentityUseCase.findById(serviceIdentityId)));
    }
}
