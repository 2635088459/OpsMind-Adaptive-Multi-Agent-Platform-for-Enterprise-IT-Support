package com.opsmind.identity.api.admin;

import com.opsmind.identity.api.IdentityRequestContext;
import com.opsmind.identity.application.command.RequireIdentityPermissionCommand;
import com.opsmind.identity.application.dto.IdentityAuditRecordView;
import com.opsmind.identity.application.port.in.EnforceIdentityPermissionUseCase;
import com.opsmind.identity.application.port.in.QueryAuditRecordsUseCase;
import com.opsmind.identity.application.query.QueryAuditRecordsByCorrelationIdQuery;
import com.opsmind.identity.config.BrowserLoginProperties;
import com.opsmind.identity.domain.role.RolePermissionCatalog;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SPEC-UA-031 (11-security: audit access is itself audited). Wires the
 * real, already-built {@link com.opsmind.identity.application.port.out.AuditPort#findByCorrelationId}
 * to an admin-only endpoint for the first time — gated by {@code
 * identity:user:admin} via {@link EnforceIdentityPermissionUseCase}, the
 * same real per-endpoint RBAC gate {@link UserIdentityAdminController} uses.
 */
@RestController
public class AuditRecordAdminController {

    private final QueryAuditRecordsUseCase queryAuditRecordsUseCase;
    private final EnforceIdentityPermissionUseCase enforceIdentityPermissionUseCase;
    private final BrowserLoginProperties browserLoginProperties;

    public AuditRecordAdminController(
        QueryAuditRecordsUseCase queryAuditRecordsUseCase, EnforceIdentityPermissionUseCase enforceIdentityPermissionUseCase,
        BrowserLoginProperties browserLoginProperties
    ) {
        this.queryAuditRecordsUseCase = queryAuditRecordsUseCase;
        this.enforceIdentityPermissionUseCase = enforceIdentityPermissionUseCase;
        this.browserLoginProperties = browserLoginProperties;
    }

    @GetMapping("/internal/identity/v1/admin/audit-records")
    public ResponseEntity<List<IdentityAuditRecordView>> findByCorrelationId(
        @RequestParam String correlationId, Authentication authentication, HttpServletRequest httpRequest
    ) {
        String requestCorrelationId = IdentityRequestContext.correlationId(httpRequest);
        IdentityRequestContext.VerifiedIssuerAndSubject verified = IdentityRequestContext.verifiedIssuerAndSubject(authentication);
        enforceIdentityPermissionUseCase.require(new RequireIdentityPermissionCommand(
            browserLoginProperties.defaultTenantId(), verified.issuer(), verified.subject(), RolePermissionCatalog.USER_ADMIN, requestCorrelationId
        ));

        List<IdentityAuditRecordView> found = queryAuditRecordsUseCase.findByCorrelationId(new QueryAuditRecordsByCorrelationIdQuery(
            browserLoginProperties.defaultTenantId(), verified.subject(), correlationId, requestCorrelationId
        )).stream().map(IdentityAuditRecordView::from).toList();
        return ResponseEntity.ok(found);
    }
}
