package dev.opsmind.ticketworkflow.ticket.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.support.AbstractTicketAssignmentIT;
import dev.opsmind.ticketworkflow.support.TestJwtSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-013 persistence/E2E: full Spring context + real PostgreSQL
 * Testcontainer + real signed JWTs. Creates the open request via SPEC-TW-012's
 * real endpoint (rather than hand-seeding the row) so the whole "request ->
 * reply -> resume" loop runs through actual application code end to end,
 * mirroring the exit-criteria E2E scenario from both specs' test plans.
 */
@Tag("integration")
class TicketUserReplyAndResumeIT extends AbstractTicketAssignmentIT {

    private static final String REQUEST_INPUT_SCOPE = "ticket:request-user-input";
    private static final String REPLY_SCOPE = "tickets:message:self";
    private static final String PROMPT = "Please upload a screenshot of the error and confirm whether the laptop is connected to VPN.";
    private static final String REPLY_BODY = "The laptop is connected to VPN and I attached the screenshot of the enrollment error.";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String supportToken(Set<String> allowedTeamIds) {
        return TestJwtSupport.mintToken(
            "sam.support", "support-console", Set.of(REQUEST_INPUT_SCOPE),
            Map.of("actor_type", "IT_SUPPORT", "support_teams", java.util.List.copyOf(allowedTeamIds))
        );
    }

    private String requesterToken(String subject) {
        return TestJwtSupport.mintToken(subject, "employee-portal", Set.of(REPLY_SCOPE), Map.of("actor_type", "EMPLOYEE"));
    }

    private ResponseEntity<String> post(String path, String bearerToken, String ifMatch, String idempotencyKey, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(bearerToken);
        if (ifMatch != null) {
            headers.set("If-Match", ifMatch);
        }
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        return restTemplate.exchange(path, HttpMethod.POST, entity, String.class);
    }

    private ResponseEntity<String> requestUserInput(UUID ticketId, Set<String> allowedTeamIds, String ifMatch, String idempotencyKey) {
        return post(
            "/api/v1/tickets/" + ticketId + "/user-input-requests", supportToken(allowedTeamIds), ifMatch, idempotencyKey,
            "{\"prompt\":\"" + PROMPT + "\"}"
        );
    }

    private ResponseEntity<String> reply(UUID ticketId, UUID requestId, String subject, String ifMatch, String idempotencyKey, String body) {
        return post(
            "/api/v1/tickets/" + ticketId + "/user-input-requests/" + requestId + "/reply", requesterToken(subject), ifMatch, idempotencyKey,
            "{\"body\":\"" + body + "\"}"
        );
    }

    private UUID extractRequestId(String responseBody) throws Exception {
        return UUID.fromString(objectMapper.readTree(responseBody).get("requestId").asText());
    }

    @Test
    void shouldReplyToTheCurrentOpenRequestAndResumeTheTicket() throws Exception {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);

        ResponseEntity<String> requestResponse = requestUserInput(ticketId, Set.of(DEFAULT_TEAM_ID), "\"0\"", "req-input-1");
        assertThat(requestResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID requestId = extractRequestId(requestResponse.getBody());

        ResponseEntity<String> replyResponse = reply(ticketId, requestId, DEFAULT_REQUESTER, "\"1\"", "reply-1", REPLY_BODY);

        assertThat(replyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replyResponse.getHeaders().getETag()).isEqualTo("\"2\"");
        assertThat(replyResponse.getBody())
            .contains("\"status\":\"IN_PROGRESS\"")
            .contains("\"previousStatus\":\"WAITING_FOR_USER\"")
            .contains("\"resumeApplied\":true");

        Map<String, Object> ticketRow = ticketRow(ticketId);
        assertThat(ticketRow.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(ticketRow.get("waiting_for_requester_since")).isNull();
        assertThat(((Number) ticketRow.get("version")).longValue()).isEqualTo(2L);

        Map<String, Object> requestRow = jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.ticket_user_input_requests WHERE request_id = ?", requestId
        );
        assertThat(requestRow.get("request_status")).isEqualTo("ANSWERED");
        assertThat(requestRow.get("answered_message_id")).isNotNull();
        assertThat(requestRow.get("answered_at")).isNotNull();

        Map<String, Object> messageRow = jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.ticket_messages WHERE ticket_id = ? ORDER BY created_at DESC LIMIT 1", ticketId
        );
        assertThat(messageRow.get("message_type")).isEqualTo("PUBLIC_REQUESTER_MESSAGE");
        assertThat(messageRow.get("content")).isEqualTo(REPLY_BODY);
        assertThat(messageRow.get("author_id")).isEqualTo(DEFAULT_REQUESTER);

        Integer historyCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_status_history WHERE ticket_id = ? AND transition_id = 'SM-015'", Integer.class, ticketId
        );
        assertThat(historyCount).isEqualTo(1);

        Integer replyReceivedCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.user-reply-received'", Integer.class, ticketId
        );
        assertThat(replyReceivedCount).isEqualTo(1);
        Integer resumedCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.user-input-resumed'", Integer.class, ticketId
        );
        assertThat(resumedCount).isEqualTo(1);
    }

    @Test
    void shouldSaveAReplyToAStaleRequestAsAPlainMessageWithoutResuming() throws Exception {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);

        ResponseEntity<String> firstRequest = requestUserInput(ticketId, Set.of(DEFAULT_TEAM_ID), "\"0\"", "req-input-1");
        UUID firstRequestId = extractRequestId(firstRequest.getBody());
        ResponseEntity<String> firstReply = reply(ticketId, firstRequestId, DEFAULT_REQUESTER, "\"1\"", "reply-1", REPLY_BODY);
        assertThat(firstReply.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> secondRequest = requestUserInput(ticketId, Set.of(DEFAULT_TEAM_ID), "\"2\"", "req-input-2");
        assertThat(secondRequest.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> staleReply = reply(ticketId, firstRequestId, DEFAULT_REQUESTER, "\"3\"", "reply-stale", "A late reply to the first question.");

        assertThat(staleReply.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(staleReply.getBody()).contains("\"resumeApplied\":false");

        Map<String, Object> ticketRow = ticketRow(ticketId);
        assertThat(ticketRow.get("status")).isEqualTo("WAITING_FOR_USER");
        assertThat(((Number) ticketRow.get("version")).longValue()).isEqualTo(3L);

        Integer messageCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_messages WHERE ticket_id = ?", Integer.class, ticketId
        );
        assertThat(messageCount).isEqualTo(2);

        Integer resumedCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.user-input-resumed'", Integer.class, ticketId
        );
        assertThat(resumedCount).isEqualTo(1);
        Integer plainMessageCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.message.added'", Integer.class, ticketId
        );
        assertThat(plainMessageCount).isEqualTo(1);
    }

    @Test
    void shouldReplayAnIdenticalRetryWithoutDuplicateSideEffects() throws Exception {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);
        ResponseEntity<String> requestResponse = requestUserInput(ticketId, Set.of(DEFAULT_TEAM_ID), "\"0\"", "req-input-1");
        UUID requestId = extractRequestId(requestResponse.getBody());

        ResponseEntity<String> first = reply(ticketId, requestId, DEFAULT_REQUESTER, "\"1\"", "reply-replay", REPLY_BODY);
        ResponseEntity<String> replay = reply(ticketId, requestId, DEFAULT_REQUESTER, "\"1\"", "reply-replay", REPLY_BODY);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(first.getBody());

        Integer messageCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_messages WHERE ticket_id = ?", Integer.class, ticketId
        );
        assertThat(messageCount).isEqualTo(1);
        assertThat(((Number) ticketRow(ticketId).get("version")).longValue()).isEqualTo(2L);
    }

    @Test
    void shouldRejectAReplyFromAnActorWhoIsNotTheTicketsRequester() throws Exception {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);
        ResponseEntity<String> requestResponse = requestUserInput(ticketId, Set.of(DEFAULT_TEAM_ID), "\"0\"", "req-input-1");
        UUID requestId = extractRequestId(requestResponse.getBody());

        ResponseEntity<String> response = reply(ticketId, requestId, "someone-else", "\"1\"", "reply-forbidden", REPLY_BODY);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).contains("TICKET_NOT_FOUND");
    }

    @Test
    void shouldRejectAStaleVersion() throws Exception {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);
        ResponseEntity<String> requestResponse = requestUserInput(ticketId, Set.of(DEFAULT_TEAM_ID), "\"0\"", "req-input-1");
        UUID requestId = extractRequestId(requestResponse.getBody());

        ResponseEntity<String> response = reply(ticketId, requestId, DEFAULT_REQUESTER, "\"9\"", "reply-stale-version", REPLY_BODY);

        assertThat(response.getStatusCode().value()).isEqualTo(412);
        assertThat(response.getBody()).contains("VERSION_CONFLICT");
    }
}
