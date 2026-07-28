package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.support.SupportQueueFixtures;
import dev.opsmind.ticketworkflow.ticket.api.exception.RequestValidationException;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueQuery;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-TW-005 §10: page size is 1-100 (default 25), out-of-range is 400 VALIDATION_ERROR. */
@Tag("unit")
class SupportQueuePageSizeTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 25, 100})
    void shouldAcceptLimitsWithinRange(int limit) {
        SupportQueueQuery query = new SupportQueueQuery(
            SupportQueueFixtures.supportActor("support-100"), SupportQueueFixtures.scope(SupportQueueFixtures.DEFAULT_APPLICATION_CODE),
            SupportQueueFixtures.noFilters(), limit, null
        );

        assertThat(query.limit()).isEqualTo(limit);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 101, 200})
    void shouldRejectLimitsOutsideRange(int limit) {
        assertThatThrownBy(() -> new SupportQueueQuery(
            SupportQueueFixtures.supportActor("support-100"), SupportQueueFixtures.scope(SupportQueueFixtures.DEFAULT_APPLICATION_CODE),
            SupportQueueFixtures.noFilters(), limit, null
        )).isInstanceOf(RequestValidationException.class);
    }

    @Test
    void shouldRejectNullActorScopeAndFilters() {
        assertThatThrownBy(() -> new SupportQueueQuery(
            null, SupportQueueFixtures.scope(SupportQueueFixtures.DEFAULT_APPLICATION_CODE), SupportQueueFixtures.noFilters(), 25, null
        )).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SupportQueueQuery(
            SupportQueueFixtures.supportActor("support-100"), null, SupportQueueFixtures.noFilters(), 25, null
        )).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SupportQueueQuery(
            SupportQueueFixtures.supportActor("support-100"), SupportQueueFixtures.scope(SupportQueueFixtures.DEFAULT_APPLICATION_CODE), null, 25, null
        )).isInstanceOf(NullPointerException.class);
    }
}
