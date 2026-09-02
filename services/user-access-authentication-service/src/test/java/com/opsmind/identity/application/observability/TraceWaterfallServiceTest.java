package com.opsmind.identity.application.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsmind.identity.application.dto.TraceWaterfallView;
import com.opsmind.identity.application.exception.TraceNotFoundException;
import com.opsmind.identity.application.exception.TraceQueryUnavailableException;
import com.opsmind.identity.application.port.out.TempoQueryResult;
import com.opsmind.identity.application.port.out.TraceQueryPort;
import com.opsmind.identity.config.TempoQueryProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SPEC-SC-014: the merge-across-tenants logic, exercised against fixtures
 * built from the REAL, live-confirmed Tempo response shape (a running
 * `observability-stack.yml`, not assumed from docs) — {@code batches}, base64
 * span/trace/parent IDs, and string-nanosecond timestamps.
 */
@Tag("unit")
class TraceWaterfallServiceTest {

    private final TraceQueryPort client = mock(TraceQueryPort.class);
    private final TempoQueryProperties properties = new TempoQueryProperties("http://tempo.test:3200", List.of("ticket-workflow", "agent-runtime"));
    private final TraceWaterfallService service = new TraceWaterfallService(client, properties);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static String b64(String hex) {
        return Base64.getEncoder().encodeToString(HexFormat.of().parseHex(hex));
    }

    private JsonNode batch(String serviceName, String namespace, String spanId, String parentSpanId, long startNanos, long endNanos) throws Exception {
        String parentField = parentSpanId == null ? "" : "\"parentSpanId\":\"" + b64(parentSpanId) + "\",";
        String json = """
            {"batches":[{"resource":{"attributes":[
              {"key":"service.name","value":{"stringValue":"%s"}},
              {"key":"service.namespace","value":{"stringValue":"%s"}}
            ]},"scopeSpans":[{"scope":{"name":"x"},"spans":[
              {"traceId":"%s","spanId":"%s",%s"name":"span-%s","kind":"SPAN_KIND_SERVER","startTimeUnixNano":"%d","endTimeUnixNano":"%d","status":{"code":"STATUS_CODE_OK"}}
            ]}]}]}
            """.formatted(serviceName, namespace, b64("aa"), b64(spanId), parentField, spanId, startNanos, endNanos);
        return objectMapper.readTree(json);
    }

    @Test
    void mergesSpansFromMultipleTenantsIntoOneChronologicallyOffsetWaterfall() throws Exception {
        JsonNode ticketWorkflowBatch = batch("ticket-workflow-service", "ticket-workflow", "01", null, 1_000_000_000L, 1_020_000_000L);
        JsonNode agentRuntimeBatch = batch("agent-runtime-service", "agent-runtime", "02", "01", 1_005_000_000L, 1_015_000_000L);
        when(client.queryTrace("http://tempo.test:3200", "trace-1", "ticket-workflow")).thenReturn(new TempoQueryResult.Found(ticketWorkflowBatch));
        when(client.queryTrace("http://tempo.test:3200", "trace-1", "agent-runtime")).thenReturn(new TempoQueryResult.Found(agentRuntimeBatch));

        TraceWaterfallView view = service.fetch("trace-1");

        assertThat(view.foundInTenants()).containsExactlyInAnyOrder("ticket-workflow", "agent-runtime");
        assertThat(view.unavailableTenants()).isEmpty();
        assertThat(view.spans()).hasSize(2);
        // The ticket-workflow span started first (it's the trace's own earliest span) — offset 0.
        assertThat(view.spans().get(0).serviceName()).isEqualTo("ticket-workflow-service");
        assertThat(view.spans().get(0).startOffsetMs()).isZero();
        assertThat(view.spans().get(0).durationMs()).isEqualTo(20);
        // The agent-runtime span started 5ms later.
        assertThat(view.spans().get(1).serviceName()).isEqualTo("agent-runtime-service");
        assertThat(view.spans().get(1).startOffsetMs()).isEqualTo(5);
        assertThat(view.spans().get(1).durationMs()).isEqualTo(10);
        assertThat(view.spans().get(1).parentSpanId()).isEqualTo(view.spans().get(0).spanId());
    }

    @Test
    void everyTenantRespondingNotFoundIsARealCleanAbsence() {
        when(client.queryTrace("http://tempo.test:3200", "missing-trace", "ticket-workflow")).thenReturn(new TempoQueryResult.NotFound());
        when(client.queryTrace("http://tempo.test:3200", "missing-trace", "agent-runtime")).thenReturn(new TempoQueryResult.NotFound());

        assertThatThrownBy(() -> service.fetch("missing-trace")).isInstanceOf(TraceNotFoundException.class);
    }

    @Test
    void everyTenantFailingToRespondIsAnHonestUnavailableNotAFalseNotFound() {
        when(client.queryTrace("http://tempo.test:3200", "trace-1", "ticket-workflow")).thenReturn(new TempoQueryResult.Unavailable("connection refused"));
        when(client.queryTrace("http://tempo.test:3200", "trace-1", "agent-runtime")).thenReturn(new TempoQueryResult.Unavailable("connection refused"));

        assertThatThrownBy(() -> service.fetch("trace-1")).isInstanceOf(TraceQueryUnavailableException.class);
    }

    @Test
    void aPartialOutageStillReturnsWhateverRealSpansWereFoundWithTheGapReportedHonestly() throws Exception {
        JsonNode ticketWorkflowBatch = batch("ticket-workflow-service", "ticket-workflow", "01", null, 1_000_000_000L, 1_020_000_000L);
        when(client.queryTrace("http://tempo.test:3200", "trace-1", "ticket-workflow")).thenReturn(new TempoQueryResult.Found(ticketWorkflowBatch));
        when(client.queryTrace("http://tempo.test:3200", "trace-1", "agent-runtime")).thenReturn(new TempoQueryResult.Unavailable("timeout"));

        TraceWaterfallView view = service.fetch("trace-1");

        assertThat(view.spans()).hasSize(1);
        assertThat(view.foundInTenants()).containsExactly("ticket-workflow");
        assertThat(view.unavailableTenants()).containsExactly("agent-runtime");
    }
}
