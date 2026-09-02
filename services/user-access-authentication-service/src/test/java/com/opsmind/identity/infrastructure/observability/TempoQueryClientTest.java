package com.opsmind.identity.infrastructure.observability;

import com.opsmind.identity.application.port.out.TempoQueryResult;
import com.opsmind.identity.support.StubHttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-SC-014: real HTTP against a stub standing in for Tempo's own {@code
 * GET /api/traces/{traceId}} — the {@code X-Scope-OrgID} header routing and
 * the 3-way Found/NotFound/Unavailable split are only meaningfully tested
 * over the wire, matching {@code OidcDiscoveryClientTest}'s own established
 * house style for this codebase's external HTTP clients.
 */
@Tag("unit")
class TempoQueryClientTest {

    private final TempoQueryClient client = new TempoQueryClient(RestClient.create());

    private static final String REAL_TEMPO_BATCH = """
        {"batches":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"ticket-workflow-service"}},{"key":"service.namespace","value":{"stringValue":"ticket-workflow"}}]},"scopeSpans":[{"scope":{"name":"manual-test"},"spans":[{"traceId":"x7Mil2YUR/O2KHKHj6ZRMA==","spanId":"O3bmfjedSkI=","name":"parent-span","kind":"SPAN_KIND_SERVER","startTimeUnixNano":"1788374729389098880","endTimeUnixNano":"1788374729419098880","status":{"code":"STATUS_CODE_OK"}}]}]}]}
        """;

    @Test
    void aFoundTraceParsesTheRealOtlpJsonBatchShape() {
        try (StubHttpServer server = StubHttpServer.create()) {
            server.routeByHeader("/api/traces/abc123", "X-Scope-OrgID", Map.of("ticket-workflow", REAL_TEMPO_BATCH));
            server.start();

            TempoQueryResult result = client.queryTrace(server.baseUrl(), "abc123", "ticket-workflow");

            assertThat(result).isInstanceOf(TempoQueryResult.Found.class);
            var found = (TempoQueryResult.Found) result;
            assertThat(found.payload().path("batches").get(0).path("resource").path("attributes").get(0).path("value").path("stringValue").asText())
                .isEqualTo("ticket-workflow-service");
        }
    }

    @Test
    void aTenantWithNoDataForThisTraceIsARealNotFoundNotAnError() {
        try (StubHttpServer server = StubHttpServer.create()) {
            server.routeByHeader("/api/traces/abc123", "X-Scope-OrgID", Map.of("ticket-workflow", REAL_TEMPO_BATCH));
            server.start();

            TempoQueryResult result = client.queryTrace(server.baseUrl(), "abc123", "agent-runtime");

            assertThat(result).isInstanceOf(TempoQueryResult.NotFound.class);
        }
    }

    @Test
    void anUnreachableHostIsUnavailableNotNotFound() {
        TempoQueryResult result = client.queryTrace("http://localhost:1", "abc123", "ticket-workflow");

        assertThat(result).isInstanceOf(TempoQueryResult.Unavailable.class);
    }
}
