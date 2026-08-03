package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTicketAssignmentIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-008: the version/state-guarded {@code UPDATE} is the sole
 * concurrency authority. Mirrors {@code TriageTicketConcurrentVersionIT}'s
 * {@code ExecutorService}/{@code invokeAll} pattern: N concurrent Assign
 * requests against one TRIAGED ticket at version 0, same {@code If-Match:
 * "0"}, each a DIFFERENT {@code Idempotency-Key} so idempotency dedup
 * cannot mask the race — exactly one {@code 200}, every other request
 * {@code 412}.
 */
@Tag("integration")
class TicketAssignmentConcurrentVersionIT extends AbstractTicketAssignmentIT {

    private static final int CONCURRENT_REQUESTS = 20;

    @Test
    void shouldAllowExactlyOneWinnerAmongConcurrentAssignAttemptsAtTheSameVersion() throws Exception {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedTriagedTicketReadyToAssign(DEFAULT_TEAM_ID, queueId);
        seedSupportAgent(DEFAULT_ASSIGNEE_ID, "IT_SUPPORT", true);
        seedQueueMembership(DEFAULT_ASSIGNEE_ID, queueId);
        String bearerToken = supportToken("support-100", Set.of(DEFAULT_TEAM_ID));
        String body = assignRequestBody(DEFAULT_ASSIGNEE_ID, DEFAULT_REASON);

        ExecutorService executor = Executors.newFixedThreadPool(10);
        try {
            List<Callable<ResponseEntity<String>>> tasks = IntStream.range(0, CONCURRENT_REQUESTS)
                .<Callable<ResponseEntity<String>>>mapToObj(i -> () -> assign(ticketId, bearerToken, "\"0\"", UUID.randomUUID().toString(), body))
                .collect(Collectors.toList());

            List<Future<ResponseEntity<String>>> futures = executor.invokeAll(tasks, 60, TimeUnit.SECONDS);
            List<ResponseEntity<String>> responses = futures.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).collect(Collectors.toList());

            long successCount = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.OK).count();
            long conflictCount = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.valueOf(412)).count();

            assertThat(successCount).isEqualTo(1);
            assertThat(conflictCount).isEqualTo(CONCURRENT_REQUESTS - 1);

            assertThat(ticketRow(ticketId).get("version")).isEqualTo(1L);
            assertThat(ticketRow(ticketId).get("status")).isEqualTo("ASSIGNED");
            assertThat(countRows("ticket.ticket_assignment_history")).isEqualTo(1);
            assertThat(countRows("ticket.ticket_status_history")).isEqualTo(1);
            assertThat(countRows("ticket.audit_records")).isEqualTo(1);
            assertThat(countRows("ticket.outbox_events")).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }
}
