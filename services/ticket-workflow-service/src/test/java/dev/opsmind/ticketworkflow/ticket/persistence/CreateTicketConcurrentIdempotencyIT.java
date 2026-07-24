package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractCreateTicketIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class CreateTicketConcurrentIdempotencyIT extends AbstractCreateTicketIT {

    private static final int CONCURRENT_REQUESTS = 100;

    @Test
    void shouldCreateExactlyOneTicketFromOneHundredConcurrentDuplicateRequests() throws Exception {
        String subject = "user-concurrent-1";
        String idempotencyKey = newIdempotencyKey();
        String body = validRequestBody();
        String bearerToken = bearerTokenFor(subject);

        ExecutorService executor = Executors.newFixedThreadPool(20);
        try {
            List<Callable<ResponseEntity<String>>> tasks = IntStream.range(0, CONCURRENT_REQUESTS)
                .<Callable<ResponseEntity<String>>>mapToObj(i -> () -> createTicketWithToken(bearerToken, idempotencyKey, body))
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

            long createdCount = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count();
            long conflictOrOtherCount = responses.size() - createdCount;

            assertThat(createdCount).isGreaterThanOrEqualTo(1);
            assertThat(responses).allSatisfy(response ->
                assertThat(response.getStatusCode())
                    .satisfiesAnyOf(
                        status -> assertThat(status).isEqualTo(HttpStatus.CREATED),
                        status -> assertThat(status).isEqualTo(HttpStatus.CONFLICT)
                    )
            );
            assertThat(conflictOrOtherCount).isGreaterThanOrEqualTo(0);

            assertThat(countRows("ticket.tickets")).isEqualTo(1);
            assertThat(countRows("ticket.outbox_events")).isEqualTo(1);
            assertThat(countRows("ticket.ticket_resolution_cycles")).isEqualTo(1);
            assertThat(countRows("ticket.ticket_sla_cycles")).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }
}
