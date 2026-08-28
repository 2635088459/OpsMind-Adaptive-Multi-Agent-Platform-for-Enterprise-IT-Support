package com.opsmind.identity.api.admin;

import com.opsmind.identity.api.IdentityRequestContext;
import com.opsmind.identity.application.command.DisableServiceIdentityCommand;
import com.opsmind.identity.application.command.RegisterServiceIdentityCommand;
import com.opsmind.identity.application.command.ValidateWorkloadIdentityCommand;
import com.opsmind.identity.application.dto.RegisterServiceIdentityRequest;
import com.opsmind.identity.application.dto.ServiceIdentityView;
import com.opsmind.identity.application.dto.WorkloadIdentityView;
import com.opsmind.identity.application.port.in.ManageServiceIdentityUseCase;
import com.opsmind.identity.application.port.in.ValidateWorkloadIdentityUseCase;
import com.opsmind.identity.config.BrowserLoginProperties;
import com.opsmind.identity.domain.workload.ServiceIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 04-use-cases §Workload identity — administrative registration/disablement of a {@code ServiceIdentity}, plus SPEC-UA-010's real self-validation. */
@RestController
public class ServiceIdentityController {

    private final ManageServiceIdentityUseCase manageServiceIdentityUseCase;
    private final ValidateWorkloadIdentityUseCase validateWorkloadIdentityUseCase;
    private final BrowserLoginProperties browserLoginProperties;

    public ServiceIdentityController(
        ManageServiceIdentityUseCase manageServiceIdentityUseCase, ValidateWorkloadIdentityUseCase validateWorkloadIdentityUseCase,
        BrowserLoginProperties browserLoginProperties
    ) {
        this.manageServiceIdentityUseCase = manageServiceIdentityUseCase;
        this.validateWorkloadIdentityUseCase = validateWorkloadIdentityUseCase;
        this.browserLoginProperties = browserLoginProperties;
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

    /**
     * SPEC-UA-010: self-validation — the caller proves it is a trusted
     * workload by presenting its own already-verified client-credentials
     * bearer JWT; every field this checks (issuer, subject, {@code aud},
     * {@code scope}) comes only from that verified token, never a request
     * body (02-business-invariants #7).
     */
    @PostMapping("/internal/identity/v1/service-identities/validate")
    public ResponseEntity<WorkloadIdentityView> validate(Authentication authentication, HttpServletRequest httpRequest) {
        Jwt jwt = IdentityRequestContext.verifiedJwt(authentication);
        IdentityRequestContext.VerifiedIssuerAndSubject verified = IdentityRequestContext.verifiedIssuerAndSubject(authentication);
        ValidateWorkloadIdentityCommand command = new ValidateWorkloadIdentityCommand(
            browserLoginProperties.defaultTenantId(), verified.issuer(), verified.subject(), tokenAudiences(jwt), tokenScopes(jwt),
            IdentityRequestContext.correlationId(httpRequest)
        );
        return ResponseEntity.ok(validateWorkloadIdentityUseCase.validate(command));
    }

    private static List<String> tokenAudiences(Jwt jwt) {
        List<String> audience = jwt.getAudience();
        return audience == null ? List.of() : audience;
    }

    /** OAuth2's own convention: a space-delimited {@code scope} claim; falls back to Keycloak's array-shaped {@code scp} claim if present instead. */
    private static List<String> tokenScopes(Jwt jwt) {
        String scopeClaim = jwt.getClaimAsString("scope");
        if (scopeClaim != null && !scopeClaim.isBlank()) {
            return List.of(scopeClaim.trim().split("\\s+"));
        }
        List<String> scp = jwt.getClaimAsStringList("scp");
        return scp == null ? List.of() : scp;
    }
}
