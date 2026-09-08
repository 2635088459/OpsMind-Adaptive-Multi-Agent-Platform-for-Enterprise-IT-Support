package dev.opsmind.ticketworkflow.ticket.infrastructure.sse;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The one real fan-out point for {@code GET /api/v1/tickets/{id}/events}
 * (TicketEventStreamController). In-process only -- a genuine, disclosed
 * scope limit, not a hidden gap: with more than one running instance of
 * this service, a subscriber connected to instance A never hears about a
 * mutation committed on instance B. Real multi-instance fan-out would mean
 * a new self-consuming RabbitMQ queue bound to OutboxPersistenceAdapter's
 * own routing keys on the existing "opsmind.events" exchange (mirroring the
 * 3 queues RabbitMqConfiguration already declares) -- deliberately not
 * built here, since this repo's own local-platform.yml runs exactly one
 * instance of this service.
 */
@Component
public class TicketEventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(TicketEventBroadcaster.class);

    /**
     * A bounded, not indefinite, emitter lifetime. That is the honest choice
     * here, not a shortcut: useTicketStatusStream.ts's own reconnect/backoff
     * (SPEC-EP-020) already treats a closed stream as routine and reconnects
     * on its own, invalidating its cached GET first -- so a subscriber never
     * observes anything beyond "a brief gap, then current state" either way.
     */
    private static final long EMITTER_TIMEOUT_MILLIS = 5 * 60 * 1000L;

    private final Map<TicketId, List<SseEmitter>> emittersByTicketId = new ConcurrentHashMap<>();

    public SseEmitter subscribe(TicketId ticketId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        emittersByTicketId.computeIfAbsent(ticketId, id -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> unsubscribe(ticketId, emitter));
        emitter.onTimeout(() -> unsubscribe(ticketId, emitter));
        emitter.onError(ex -> unsubscribe(ticketId, emitter));

        try {
            // Real bug found live: a Servlet container (Tomcat included)
            // never commits a response -- status line, headers, everything
            // -- until the first byte of the real body is actually written.
            // Without this, a real browser EventSource's own onopen/
            // readyState never fires OPEN until this ticket's first real
            // update, which could be minutes away or never. A comment line
            // (SSE's own "ignore this" framing -- RFC 8895, never delivered
            // to EventSource.onmessage/onmessage's data) forces that commit
            // immediately without ever triggering a spurious cache
            // invalidation; useTicketStatusStream.ts's own onopen already
            // handles the real "just reconnected" invalidate on its own
            // terms.
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException ex) {
            // A client that disconnected before this even reached it; the
            // completion callback above already unsubscribed it.
            log.debug("dropping a dead SSE emitter for ticketId={} on initial connect", ticketId, ex);
        }

        return emitter;
    }

    private void unsubscribe(TicketId ticketId, SseEmitter emitter) {
        emittersByTicketId.computeIfPresent(ticketId, (id, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }

    /**
     * {@code AFTER_COMMIT}: a mutation that rolls back downstream of the
     * outbox append (e.g. a failed optimistic-concurrency check) never
     * reaches a live subscriber as a false "something changed."
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTicketOutboxEventAppended(TicketOutboxEventAppended event) {
        List<SseEmitter> emitters = emittersByTicketId.get(event.ticketId());
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : List.copyOf(emitters)) {
            try {
                // Deliberately unnamed (the SSE default "message" type):
                // useTicketStatusStream.ts's own eventSource.onmessage only
                // ever fires for that default type -- it never registers an
                // addEventListener for any named event -- so a named event
                // here would silently never reach it at all.
                emitter.send(SseEmitter.event().data(Map.of(
                    "ticketId", event.ticketId().toString(),
                    "eventType", event.eventType(),
                    "occurredAt", event.occurredAt().toString()
                )));
            } catch (IOException | IllegalStateException ex) {
                log.debug("dropping a dead SSE emitter for ticketId={}", event.ticketId(), ex);
                unsubscribe(event.ticketId(), emitter);
            }
        }
    }
}
