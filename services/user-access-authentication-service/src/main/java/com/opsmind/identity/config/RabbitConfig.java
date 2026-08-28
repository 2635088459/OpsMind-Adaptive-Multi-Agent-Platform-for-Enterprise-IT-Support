package com.opsmind.identity.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The outbound topology (SPEC-UA-003): this service is a trusted-identity
 * <em>publisher</em> on the shared {@code opsmind.events} topic exchange —
 * the same exchange ticket-workflow-service's and
 * policy-approval-governance-service's own {@code RabbitConfig}s already
 * declare and bind queues to (06-event-contracts §Published events).
 *
 * <p>SPEC-UA-028 adds this domain's first real <em>inbound</em> queues: the
 * three domain-06 approval-outcome facts ({@code approval.granted.v1},
 * {@code approval.denied.v1}, {@code approval.expired.v1}) 06-event-contracts
 * §Consumed events names — the other two named consumed-fact categories
 * (Keycloak/admin adapter facts; platform service-identity/key-rotation
 * facts) have no real producer anywhere in this monorepo, so no queue is
 * declared for them (an honest, verified gap — mirrors SPEC-UA-027's own
 * treatment of the 3 Python services with no real workload-identity
 * integration). Mirrors policy-approval-governance-service's own {@code
 * RabbitConfig} topology exactly: a durable queue with a dead-letter
 * exchange/routing key and a container factory with {@code
 * defaultRequeueRejected(false)} (reject straight to the DLQ on any
 * exception, no retry-with-backoff interceptor — nothing in this domain's
 * own LLD names a specific retry policy, so this spec does not invent one).
 */
@Configuration
public class RabbitConfig {

    public static final String IDENTITY_EVENTS_EXCHANGE = "opsmind.events";
    public static final String DEAD_LETTER_EXCHANGE = "opsmind.dlx";
    public static final String APPROVAL_GRANTED_EVENTS_QUEUE = "user-access-authentication.approval-granted-events.v1";
    public static final String APPROVAL_GRANTED_EVENTS_DLQ = "user-access-authentication.approval-granted-events.dlq.v1";
    public static final String APPROVAL_DENIED_EVENTS_QUEUE = "user-access-authentication.approval-denied-events.v1";
    public static final String APPROVAL_DENIED_EVENTS_DLQ = "user-access-authentication.approval-denied-events.dlq.v1";
    public static final String APPROVAL_EXPIRED_EVENTS_QUEUE = "user-access-authentication.approval-expired-events.v1";
    public static final String APPROVAL_EXPIRED_EVENTS_DLQ = "user-access-authentication.approval-expired-events.dlq.v1";
    private static final String APPROVAL_GRANTED_ROUTING_KEY = "approval.granted.v1";
    private static final String APPROVAL_DENIED_ROUTING_KEY = "approval.denied.v1";
    private static final String APPROVAL_EXPIRED_ROUTING_KEY = "approval.expired.v1";

    @Bean
    public TopicExchange opsmindEventsExchange() {
        return ExchangeBuilder.topicExchange(IDENTITY_EVENTS_EXCHANGE).durable(true).build();
    }

    @Bean
    public TopicExchange opsmindDeadLetterExchange() {
        return ExchangeBuilder.topicExchange(DEAD_LETTER_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue approvalGrantedEventsQueue() {
        return QueueBuilder.durable(APPROVAL_GRANTED_EVENTS_QUEUE)
            .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", APPROVAL_GRANTED_EVENTS_DLQ)
            .build();
    }

    @Bean
    public Queue approvalGrantedEventsDlq() {
        return QueueBuilder.durable(APPROVAL_GRANTED_EVENTS_DLQ).build();
    }

    @Bean
    public Binding approvalGrantedBinding() {
        return BindingBuilder.bind(approvalGrantedEventsQueue()).to(opsmindEventsExchange()).with(APPROVAL_GRANTED_ROUTING_KEY);
    }

    @Bean
    public Binding approvalGrantedEventsDlqBinding() {
        return BindingBuilder.bind(approvalGrantedEventsDlq()).to(opsmindDeadLetterExchange()).with(APPROVAL_GRANTED_EVENTS_DLQ);
    }

    @Bean
    public Queue approvalDeniedEventsQueue() {
        return QueueBuilder.durable(APPROVAL_DENIED_EVENTS_QUEUE)
            .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", APPROVAL_DENIED_EVENTS_DLQ)
            .build();
    }

    @Bean
    public Queue approvalDeniedEventsDlq() {
        return QueueBuilder.durable(APPROVAL_DENIED_EVENTS_DLQ).build();
    }

    @Bean
    public Binding approvalDeniedBinding() {
        return BindingBuilder.bind(approvalDeniedEventsQueue()).to(opsmindEventsExchange()).with(APPROVAL_DENIED_ROUTING_KEY);
    }

    @Bean
    public Binding approvalDeniedEventsDlqBinding() {
        return BindingBuilder.bind(approvalDeniedEventsDlq()).to(opsmindDeadLetterExchange()).with(APPROVAL_DENIED_EVENTS_DLQ);
    }

    @Bean
    public Queue approvalExpiredEventsQueue() {
        return QueueBuilder.durable(APPROVAL_EXPIRED_EVENTS_QUEUE)
            .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", APPROVAL_EXPIRED_EVENTS_DLQ)
            .build();
    }

    @Bean
    public Queue approvalExpiredEventsDlq() {
        return QueueBuilder.durable(APPROVAL_EXPIRED_EVENTS_DLQ).build();
    }

    @Bean
    public Binding approvalExpiredBinding() {
        return BindingBuilder.bind(approvalExpiredEventsQueue()).to(opsmindEventsExchange()).with(APPROVAL_EXPIRED_ROUTING_KEY);
    }

    @Bean
    public Binding approvalExpiredEventsDlqBinding() {
        return BindingBuilder.bind(approvalExpiredEventsDlq()).to(opsmindDeadLetterExchange()).with(APPROVAL_EXPIRED_EVENTS_DLQ);
    }

    /** Shared by all three queues above — none of them need distinct settings. */
    @Bean
    public SimpleRabbitListenerContainerFactory approvalDecisionEventsListenerContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
