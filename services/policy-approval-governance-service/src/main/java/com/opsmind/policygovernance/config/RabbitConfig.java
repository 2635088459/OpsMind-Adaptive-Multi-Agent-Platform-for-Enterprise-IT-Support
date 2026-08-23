package com.opsmind.policygovernance.config;

import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares only the outbound topology: this service is a governance-fact
 * <em>publisher</em> on the shared {@code opsmind.events} topic exchange —
 * the same exchange ticket-workflow-service's own {@code
 * RabbitMqConfiguration} already declares and binds queues to, so this
 * service's {@code approval.granted.v1}/{@code approval.denied.v1}/etc.
 * routing keys land wherever a downstream domain has already bound a queue.
 * No queue/binding is declared here: this spec builds no consumer (06's own
 * consumed events — {@code tool.approval.required.v1},
 * {@code workflow.approval.required.v1}, {@code ticket.approval.required.v1},
 * {@code policy.evaluation.requested.v1} — are wired by the future specs
 * that build each concrete request-creation use case, not this baseline).
 */
@Configuration
public class RabbitConfig {

    public static final String GOVERNANCE_EVENTS_EXCHANGE = "opsmind.events";

    @Bean
    public TopicExchange opsmindEventsExchange() {
        return ExchangeBuilder.topicExchange(GOVERNANCE_EVENTS_EXCHANGE).durable(true).build();
    }
}
