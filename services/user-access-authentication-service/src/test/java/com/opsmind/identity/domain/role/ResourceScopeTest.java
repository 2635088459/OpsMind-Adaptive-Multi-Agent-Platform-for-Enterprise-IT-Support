package com.opsmind.identity.domain.role;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-UA-013 (Tenant And Support Queue Scope) — the "richer tenant/queue scope model" {@link ResourceScope}'s own javadoc used to point at as still-unbuilt. */
class ResourceScopeTest {

    @Test
    void selfNeverCarriesAScopeId() {
        ResourceScope scope = new ResourceScope(ResourceScope.ScopeType.SELF, null);
        assertThat(scope.scopeId()).isNull();
    }

    @Test
    void selfWithANonNullScopeIdIsRejected() {
        assertThatThrownBy(() -> new ResourceScope(ResourceScope.ScopeType.SELF, "anything"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tenantNeverCarriesAScopeId() {
        ResourceScope scope = ResourceScope.tenantWide();
        assertThat(scope.scopeId()).isNull();
    }

    @Test
    void tenantWithANonNullScopeIdIsRejected() {
        assertThatThrownBy(() -> new ResourceScope(ResourceScope.ScopeType.TENANT, "tenant-1"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void supportQueueRequiresANonBlankScopeId() {
        ResourceScope scope = new ResourceScope(ResourceScope.ScopeType.SUPPORT_QUEUE, "billing");
        assertThat(scope.scopeId()).isEqualTo("billing");
    }

    @Test
    void supportQueueWithANullScopeIdIsRejected() {
        assertThatThrownBy(() -> new ResourceScope(ResourceScope.ScopeType.SUPPORT_QUEUE, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void supportQueueWithABlankScopeIdIsRejected() {
        assertThatThrownBy(() -> new ResourceScope(ResourceScope.ScopeType.SUPPORT_QUEUE, "   "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resourceRequiresANonBlankScopeId() {
        ResourceScope scope = new ResourceScope(ResourceScope.ScopeType.RESOURCE, "ticket-42");
        assertThat(scope.scopeId()).isEqualTo("ticket-42");
    }

    @Test
    void resourceWithANullScopeIdIsRejected() {
        assertThatThrownBy(() -> new ResourceScope(ResourceScope.ScopeType.RESOURCE, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scopeTypeMustNotBeNull() {
        assertThatThrownBy(() -> new ResourceScope(null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** SPEC-UA-014 (Authorization Context And Decision API) — the real scope-intersection algorithm. */
    @Test
    void aNullRequiredScopeIsAlwaysCovered() {
        assertThat(ResourceScope.tenantWide().covers(null)).isTrue();
        assertThat(new ResourceScope(ResourceScope.ScopeType.SUPPORT_QUEUE, "billing").covers(null)).isTrue();
    }

    @Test
    void tenantCoversAnyNarrowerNonSelfScope() {
        ResourceScope tenant = ResourceScope.tenantWide();

        assertThat(tenant.covers(ResourceScope.tenantWide())).isTrue();
        assertThat(tenant.covers(new ResourceScope(ResourceScope.ScopeType.SUPPORT_QUEUE, "billing"))).isTrue();
        assertThat(tenant.covers(new ResourceScope(ResourceScope.ScopeType.RESOURCE, "ticket-42"))).isTrue();
    }

    @Test
    void tenantNeverCoversSelfSinceOwnershipIsADifferentAxis() {
        assertThat(ResourceScope.tenantWide().covers(new ResourceScope(ResourceScope.ScopeType.SELF, null))).isFalse();
    }

    @Test
    void supportQueueOnlyCoversTheExactSameQueue() {
        ResourceScope billing = new ResourceScope(ResourceScope.ScopeType.SUPPORT_QUEUE, "billing");

        assertThat(billing.covers(new ResourceScope(ResourceScope.ScopeType.SUPPORT_QUEUE, "billing"))).isTrue();
        assertThat(billing.covers(new ResourceScope(ResourceScope.ScopeType.SUPPORT_QUEUE, "sales"))).isFalse();
        assertThat(billing.covers(ResourceScope.tenantWide())).isFalse();
        assertThat(billing.covers(new ResourceScope(ResourceScope.ScopeType.RESOURCE, "ticket-42"))).isFalse();
    }

    @Test
    void selfOnlyCoversSelf() {
        ResourceScope self = new ResourceScope(ResourceScope.ScopeType.SELF, null);

        assertThat(self.covers(new ResourceScope(ResourceScope.ScopeType.SELF, null))).isTrue();
        assertThat(self.covers(ResourceScope.tenantWide())).isFalse();
    }
}
