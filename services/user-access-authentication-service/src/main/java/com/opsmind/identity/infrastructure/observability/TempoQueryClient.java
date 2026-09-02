package com.opsmind.identity.infrastructure.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsmind.identity.application.port.out.TempoQueryResult;
import com.opsmind.identity.application.port.out.TraceQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * SPEC-SC-014: the real {@link TraceQueryPort} adapter — a direct HTTP
 * client for Tempo's own trace-query API (`GET /api/traces/{traceId}`,
 * `X-Scope-OrgID` for SPEC-OP-031's real per-tenant isolation). Response
 * shape confirmed LIVE against a running `observability-stack.yml` (not
 * assumed from Tempo's docs): it is raw OTLP-JSON
 * (`{"batches":[{"resource":{...},"scopeSpans":[{"spans":[...]}]}]}`), NOT
 * the Jaeger-compatible shape this spec's own author first assumed. Two
 * non-obvious, live-confirmed encoding details {@code TraceWaterfallService}
 * depends on: `traceId`/`spanId`/`parentSpanId` are base64 (proto3 JSON
 * bytes-field convention, not hex), and `startTimeUnixNano`/
 * `endTimeUnixNano` are JSON STRINGS holding a nanosecond epoch value that
 * exceeds {@code Number.MAX_SAFE_INTEGER} — both are why this parsing
 * happens here, in Java (a 64-bit {@code long} losslessly holds either),
 * rather than being handed raw to the browser.
 */
@Component
public class TempoQueryClient implements TraceQueryPort {

    private static final Logger log = LoggerFactory.getLogger(TempoQueryClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TempoQueryClient() {
        this(RestClient.create());
    }

    TempoQueryClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public TempoQueryResult queryTrace(String baseUrl, String traceId, String tenant) {
        try {
            String body = restClient.get()
                .uri(baseUrl + "/api/traces/" + traceId)
                .header("X-Scope-OrgID", tenant)
                .retrieve()
                .body(String.class);
            return new TempoQueryResult.Found(objectMapper.readTree(body));
        } catch (HttpClientErrorException.NotFound e) {
            // The real, expected outcome for every tenant this trace never touched (ADR-0011).
            return new TempoQueryResult.NotFound();
        } catch (RestClientException | java.io.UncheckedIOException e) {
            log.warn("tempo query failed for tenant {} (traceId={}): {}", tenant, traceId, e.getMessage());
            return new TempoQueryResult.Unavailable(e.getMessage());
        } catch (Exception e) {
            log.warn("tempo response for tenant {} (traceId={}) could not be parsed: {}", tenant, traceId, e.getMessage());
            return new TempoQueryResult.Unavailable("malformed response");
        }
    }
}
