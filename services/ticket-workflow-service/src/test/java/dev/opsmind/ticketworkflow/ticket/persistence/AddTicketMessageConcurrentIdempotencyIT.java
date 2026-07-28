package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractAddTicketMessageIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-004 §11/§21: 100 concurrent identical requests create exactly one Message. */
@Tag("integration")
class AddTicketMessageConcurrentIdempotencyIT extends AbstractAddTicketMessageIT {

    private static final int CONCURRENT_REQUESTS = 100;

    @Test
    void shouldCreateExactlyOneMessageFromOneHundredConcurrentDuplicateRequests() throws Exception {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "INVESTIGATING");
        String idempotencyKey = UUID.randomUUID().toString();
        String bearerToken = employeeToken(DEFAULT_REQUESTER);
        String body = employeeBody(DEFAULT_CONTENT);

        ExecutorService executor = Executors.newFixedThreadPool(20);
        try {
            List<Callable<ResponseEntity<String>>> tasks = IntStream.range(0, CONCURRENT_REQUESTS)
                .<Callable<ResponseEntity<String>>>mapToObj(i -> () -> addMessage(ticketId, bearerToken, idempotencyKey, body))
                .collect(Collectors.toList());

            List<Future<ResponseEntity<String>>> futures = executor.invokeAll(tasks, 60, TimeUnit.SECONDS);

            List<ResponseEntity<String>> responses = futures.stream()
                .map(future -> {
                    try {
                        return future.get();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .collect(Collectors.toList());

            assertThat(responses).allSatisfy(response ->
                assertThat(response.getStatusCode())
                    .satisfiesAnyOf(
                        status -> assertThat(status).isEqualTo(HttpStatus.CREATED),
                        status -> assertThat(status).isEqualTo(HttpStatus.CONFLICT)
                    )
            );

            assertThat(countRows("ticket.ticket_messages")).isEqualTo(1);
            assertThat(countRows("ticket.audit_records")).isEqualTo(1);
            assertThat(countRows("ticket.outbox_events")).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }
}
