package com.opsmind.identity.api.internal;

import com.opsmind.identity.api.IdentityRequestContext;
import com.opsmind.identity.application.command.LinkUserIdentityCommand;
import com.opsmind.identity.application.dto.EffectiveRoleView;
import com.opsmind.identity.application.dto.MyProfileView;
import com.opsmind.identity.application.port.in.ManageRoleAssignmentUseCase;
import com.opsmind.identity.application.port.in.ProvisionUserUseCase;
import com.opsmind.identity.application.query.ListRoleAssignmentsQuery;
import com.opsmind.identity.config.BrowserLoginProperties;
import com.opsmind.identity.domain.user.IdentityType;
import com.opsmind.identity.domain.user.UserIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 05-api-contracts {@code GET /users/me}: "Minimum profile plus effective
 * roles/scopes" (SPEC-UA-007, Principal Claims Normalization). Auto-links
 * the trusted identity on first sight, mirroring the OIDC-login use case's
 * own identity-resolution step. {@code tenantId} comes from this service's
 * own server-side configuration ({@link BrowserLoginProperties#defaultTenantId()}
 * — see its own javadoc for why), never a client-supplied request
 * parameter (02-business-invariants #7).
 */
@RestController
public class UserIdentityController {

    private final ProvisionUserUseCase provisionUserUseCase;
    private final ManageRoleAssignmentUseCase manageRoleAssignmentUseCase;
    private final BrowserLoginProperties browserLoginProperties;

    public UserIdentityController(
        ProvisionUserUseCase provisionUserUseCase, ManageRoleAssignmentUseCase manageRoleAssignmentUseCase,
        BrowserLoginProperties browserLoginProperties
    ) {
        this.provisionUserUseCase = provisionUserUseCase;
        this.manageRoleAssignmentUseCase = manageRoleAssignmentUseCase;
        this.browserLoginProperties = browserLoginProperties;
    }

    @GetMapping("/internal/identity/v1/users/me")
    public ResponseEntity<MyProfileView> me(Authentication authentication, HttpServletRequest httpRequest) {
        IdentityRequestContext.VerifiedIssuerAndSubject verified = IdentityRequestContext.verifiedIssuerAndSubject(authentication);
        LinkUserIdentityCommand command = new LinkUserIdentityCommand(
            browserLoginProperties.defaultTenantId(), verified.issuer(), verified.subject(), null, null, null, IdentityType.HUMAN,
            IdentityRequestContext.correlationId(httpRequest)
        );
        UserIdentity linked = provisionUserUseCase.link(command);

        var effectiveRoles = manageRoleAssignmentUseCase.listEffectiveForUser(new ListRoleAssignmentsQuery(linked.userIdentityId()))
            .stream().map(EffectiveRoleView::from).toList();

        return ResponseEntity.ok(MyProfileView.of(linked, effectiveRoles));
    }
}
