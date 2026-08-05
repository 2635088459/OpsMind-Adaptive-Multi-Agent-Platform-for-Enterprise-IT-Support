package dev.opsmind.ticketworkflow.configuration;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.Map;

/**
 * SPEC-TW-015 / 06-event-contracts §4: the inbound RabbitMQ topology for
 * approval events — the {@code opsmind.events} exchange, the {@code
 * ticket-workflow.approval-events.v1} queue, and its DLQ. SPEC-TW-015 bound
 * {@code approval.granted.v1}; SPEC-TW-016 added {@code approval.rejected.v1};
 * SPEC-TW-017 added {@code approval.expired.v1}; SPEC-TW-018 adds {@code
 * policy.action-auto-approved.v1}. All bindings feed the same queue, and {@code
 * dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.consumer.ApprovalEventsDispatcher}
 * is deliberately the queue's only {@code @RabbitListener}, dispatching by
 * {@code eventType} to the per-event-type consumer — see that class for why
 * multiple listeners on one queue do not work under {@code
 * x-single-active-consumer}.
 * <p>
 * Retry policy: only the transient database-unavailability exceptions this
 * codebase already treats as retryable at the HTTP layer ({@code
 * DataAccessResourceFailureException}, {@code CannotCreateTransactionException},
 * see {@code GlobalRestExceptionHandler}) get a bounded number of retries
 * with backoff; every other exception — including every {@code
 * NonRetryableConsumedEventException} — is rejected to the DLQ on the first
 * attempt (06-event-contracts §14).
 */
@Configuration
public class RabbitMqConfiguration {

    public static final String EVENTS_EXCHANGE = "opsmind.events";
    public static final String DEAD_LETTER_EXCHANGE = "opsmind.dlx";
    public static final String APPROVAL_EVENTS_QUEUE = "ticket-workflow.approval-events.v1";
    public static final String APPROVAL_EVENTS_DLQ = "ticket-workflow.approval-events.dlq.v1";
    private static final String APPROVAL_GRANTED_ROUTING_KEY = "approval.granted.v1";
    private static final String APPROVAL_REJECTED_ROUTING_KEY = "approval.rejected.v1";
    private static final String APPROVAL_EXPIRED_ROUTING_KEY = "approval.expired.v1";
    private static final String POLICY_ACTION_AUTO_APPROVED_ROUTING_KEY = "policy.action-auto-approved.v1";

    @Bean
    public TopicExchange opsmindEventsExchange() {
        return ExchangeBuilder.topicExchange(EVENTS_EXCHANGE).durable(true).build();
    }

    @Bean
    public TopicExchange opsmindDeadLetterExchange() {
        return ExchangeBuilder.topicExchange(DEAD_LETTER_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue approvalEventsQueue() {
        return QueueBuilder.durable(APPROVAL_EVENTS_QUEUE)
            .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", APPROVAL_EVENTS_DLQ)
            .withArgument("x-single-active-consumer", true)
            .build();
    }

    @Bean
    public Queue approvalEventsDlq() {
        return QueueBuilder.durable(APPROVAL_EVENTS_DLQ).build();
    }

    @Bean
    public Binding approvalGrantedBinding() {
        return BindingBuilder.bind(approvalEventsQueue()).to(opsmindEventsExchange()).with(APPROVAL_GRANTED_ROUTING_KEY);
    }

    @Bean
    public Binding approvalRejectedBinding() {
        return BindingBuilder.bind(approvalEventsQueue()).to(opsmindEventsExchange()).with(APPROVAL_REJECTED_ROUTING_KEY);
    }

    @Bean
    public Binding approvalExpiredBinding() {
        return BindingBuilder.bind(approvalEventsQueue()).to(opsmindEventsExchange()).with(APPROVAL_EXPIRED_ROUTING_KEY);
    }

    @Bean
    public Binding policyActionAutoApprovedBinding() {
        return BindingBuilder.bind(approvalEventsQueue()).to(opsmindEventsExchange()).with(POLICY_ACTION_AUTO_APPROVED_ROUTING_KEY);
    }

    @Bean
    public Binding approvalEventsDlqBinding() {
        return BindingBuilder.bind(approvalEventsDlq()).to(opsmindDeadLetterExchange()).with(APPROVAL_EVENTS_DLQ);
    }

    @Bean
    public RetryOperationsInterceptor approvalEventsRetryInterceptor() {
        Map<Class<? extends Throwable>, Boolean> retryableExceptions = Map.of(
            DataAccessResourceFailureException.class, true,
            CannotCreateTransactionException.class, true
        );
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(3, retryableExceptions, true, false);

        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(200L);
        backOffPolicy.setMultiplier(3.0);
        backOffPolicy.setMaxInterval(2000L);

        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(retryPolicy);
        retryTemplate.setBackOffPolicy(backOffPolicy);

        return RetryInterceptorBuilder.stateless()
            .retryOperations(retryTemplate)
            .recoverer(new RejectAndDontRequeueRecoverer())
            .build();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory approvalEventsListenerContainerFactory(
        ConnectionFactory connectionFactory, RetryOperationsInterceptor approvalEventsRetryInterceptor
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(approvalEventsRetryInterceptor);
        return factory;
    }
}
