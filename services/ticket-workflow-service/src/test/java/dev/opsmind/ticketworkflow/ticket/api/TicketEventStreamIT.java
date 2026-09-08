package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.support.AbstractTriageTicketIT;
import dev.opsmind.ticketworkflow.support.TestJwtSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-EP-014/020's own real backend counterpart to
 * useTicketStatusStream.ts (TicketEventStreamController). Proves the one
 * thing no MockMvc/WebMvcTest can: a real signed JWT via a {@code ?token=}
 * query parameter authenticates a real chunked HTTP stream (the actual
 * mechanism a browser {@code EventSource} depends on), and a real,
 * committed ticket mutation (Triage, SPEC-TW-007) genuinely reaches that
 * open stream -- the exact transaction-timing question
 * TicketEventBroadcaster's own {@code @TransactionalEventListener
 * (AFTER_COMMIT)} exists to answer correctly, not merely assumed.
 */
@Tag("integration")
class TicketEventStreamIT extends AbstractTriageTicketIT {

    @Test
    void deliversATicketUpdatedEventAfterARealCommittedTriage() throws Exception {
        UUID ticketId = seedOpenTicket();
        UUID categoryId = seedCategory(true);
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);

        String employeeToken = TestJwtSupport.mintToken(
            DEFAULT_REQUESTER, "employee-portal", Set.of("tickets:read:self"), Map.of("actor_type", "EMPLOYEE")
        );

        // Real bug found live: Java's HttpClient, left at its default
        // HTTP_2-preferred version, hangs its own synchronous send(...) call
        // indefinitely against this exact kind of indefinitely-open,
        // no-Content-Length streaming response over plaintext HTTP -- it
        // never falls back to HTTP/1.1 in time to see the real headers this
        // server already sent. Forcing HTTP/1.1 (this test's own client
        // only, not the real Spring MVC/Tomcat server side, which never
        // negotiated h2c to begin with) is the standard fix.
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        URI streamUri = URI.create(restTemplate.getRootUri() + "/api/v1/tickets/" + ticketId + "/events?token=" + employeeToken);
        HttpRequest streamRequest = HttpRequest.newBuilder(streamUri).GET().timeout(Duration.ofSeconds(15)).build();
        HttpResponse<InputStream> streamResponse = client.send(streamRequest, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(streamResponse.statusCode()).as("SSE connection should be authorized").isEqualTo(200);

        // Deliberately watches for a "data:" line, not "event:" -- the real
        // broadcast is an unnamed (default "message"-type) SSE event on
        // purpose (see TicketEventBroadcaster's own docstring: a named
        // event would never reach useTicketStatusStream.ts's own
        // eventSource.onmessage at all), so this is the real signal a
        // genuine EventSource.onmessage would fire on too.
        BlockingQueue<String> receivedDataPayloads = new ArrayBlockingQueue<>(10);
        Thread streamReader = new Thread(() -> {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(streamResponse.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        receivedDataPayloads.offer(line.substring("data:".length()).trim());
                    }
                }
            } catch (IOException ignored) {
                // Expected once this test closes streamResponse.body() below.
            }
        });
        streamReader.setDaemon(true);
        streamReader.start();

        try {
            ResponseEntity<String> triageResponse = triage(
                ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
                triageRequestBody(categoryId, null, "HIGH", queueId)
            );
            assertThat(triageResponse.getStatusCode()).as(triageResponse.getBody()).isEqualTo(HttpStatus.OK);

            String receivedDataPayload = receivedDataPayloads.poll(10, TimeUnit.SECONDS);
            assertThat(receivedDataPayload).as("the open SSE stream should hear about the real committed triage").contains(ticketId.toString());
        } finally {
            streamResponse.body().close();
        }
    }

    @Test
    void rejectsAConnectionForAnActorMissingTheReadSelfScope() throws Exception {
        UUID ticketId = seedOpenTicket();

        String employeeTokenWithoutReadScope = TestJwtSupport.mintToken(
            DEFAULT_REQUESTER, "employee-portal", Set.of(), Map.of("actor_type", "EMPLOYEE")
        );

        HttpClient client = HttpClient.newHttpClient();
        URI streamUri = URI.create(restTemplate.getRootUri() + "/api/v1/tickets/" + ticketId + "/events?token=" + employeeTokenWithoutReadScope);
        HttpRequest streamRequest = HttpRequest.newBuilder(streamUri).GET().timeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> streamResponse = client.send(streamRequest, HttpResponse.BodyHandlers.ofString());

        assertThat(streamResponse.statusCode()).isEqualTo(403);
        assertThat(streamResponse.body()).contains("FORBIDDEN");
    }
}
