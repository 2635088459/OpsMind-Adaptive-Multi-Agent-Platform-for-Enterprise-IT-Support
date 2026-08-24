package com.opsmind.policygovernance.config;

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
 * The outbound topology (this service is a governance-fact <em>publisher</em>
 * on the shared {@code opsmind.events} topic exchange — the same exchange
 * ticket-workflow-service's own {@code RabbitMqConfiguration} already
 * declares and binds queues to) plus, since SPEC-PG-025/026/027/028, all
 * four real <em>inbound</em> queues 06-event-contracts §Consumed Events
 * names: {@code tool.approval.required.v1}, {@code
 * workflow.approval.required.v1}, {@code ticket.approval.required.v1}, and
 * {@code policy.evaluation.requested.v1} — every consumed event this
 * service's own LLD names now has a real queue/consumer, closing Phase 06.
 *
 * <p>Mirrors ticket-workflow-service's own {@code RabbitMqConfiguration}
 * topology at a proportionate scale: a durable queue with a dead-letter
 * exchange/routing key (10-failure-handling §Poison Decision: "approval
 * payload does not match source linkage" belongs in a DLQ, not an infinite
 * requeue loop) and a container factory with {@code
 * defaultRequeueRejected(false)} (reject straight to the DLQ on any
 * exception, no retry-with-backoff interceptor — nothing in 06's own LLD
 * names a specific retry policy the way ticket-workflow-service's own
 * SPEC-TW-015 did, so this spec does not invent one; a future spec can add
 * one to this same factory without an interface change).
 */
@Configuration
public class RabbitConfig {

    public static final String GOVERNANCE_EVENTS_EXCHANGE = "opsmind.events";
    public static final String DEAD_LETTER_EXCHANGE = "opsmind.dlx";
    public static final String TOOL_APPROVAL_EVENTS_QUEUE = "policy-approval-governance.tool-approval-events.v1";
    public static final String TOOL_APPROVAL_EVENTS_DLQ = "policy-approval-governance.tool-approval-events.dlq.v1";
    public static final String WORKFLOW_APPROVAL_EVENTS_QUEUE = "policy-approval-governance.workflow-approval-events.v1";
    public static final String WORKFLOW_APPROVAL_EVENTS_DLQ = "policy-approval-governance.workflow-approval-events.dlq.v1";
    public static final String TICKET_APPROVAL_EVENTS_QUEUE = "policy-approval-governance.ticket-approval-events.v1";
    public static final String TICKET_APPROVAL_EVENTS_DLQ = "policy-approval-governance.ticket-approval-events.dlq.v1";
    public static final String POLICY_EVALUATION_EVENTS_QUEUE = "policy-approval-governance.policy-evaluation-events.v1";
    public static final String POLICY_EVALUATION_EVENTS_DLQ = "policy-approval-governance.policy-evaluation-events.dlq.v1";
    private static final String TOOL_APPROVAL_REQUIRED_ROUTING_KEY = "tool.approval.required.v1";
    private static final String WORKFLOW_APPROVAL_REQUIRED_ROUTING_KEY = "workflow.approval.required.v1";
    private static final String TICKET_APPROVAL_REQUIRED_ROUTING_KEY = "ticket.approval.required.v1";
    private static final String POLICY_EVALUATION_REQUESTED_ROUTING_KEY = "policy.evaluation.requested.v1";

    @Bean
    public TopicExchange opsmindEventsExchange() {
        return ExchangeBuilder.topicExchange(GOVERNANCE_EVENTS_EXCHANGE).durable(true).build();
    }

    @Bean
    public TopicExchange opsmindDeadLetterExchange() {
        return ExchangeBuilder.topicExchange(DEAD_LETTER_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue toolApprovalEventsQueue() {
        return QueueBuilder.durable(TOOL_APPROVAL_EVENTS_QUEUE)
            .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", TOOL_APPROVAL_EVENTS_DLQ)
            .build();
    }

    @Bean
    public Queue toolApprovalEventsDlq() {
        return QueueBuilder.durable(TOOL_APPROVAL_EVENTS_DLQ).build();
    }

    @Bean
    public Binding toolApprovalRequiredBinding() {
        return BindingBuilder.bind(toolApprovalEventsQueue()).to(opsmindEventsExchange()).with(TOOL_APPROVAL_REQUIRED_ROUTING_KEY);
    }

    @Bean
    public Binding toolApprovalEventsDlqBinding() {
        return BindingBuilder.bind(toolApprovalEventsDlq()).to(opsmindDeadLetterExchange()).with(TOOL_APPROVAL_EVENTS_DLQ);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory toolApprovalEventsListenerContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    /** SPEC-PG-026: {@code workflow.approval.required.v1} — mirrors {@link #toolApprovalEventsQueue} exactly, see its own javadoc. */
    @Bean
    public Queue workflowApprovalEventsQueue() {
        return QueueBuilder.durable(WORKFLOW_APPROVAL_EVENTS_QUEUE)
            .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", WORKFLOW_APPROVAL_EVENTS_DLQ)
            .build();
    }

    @Bean
    public Queue workflowApprovalEventsDlq() {
        return QueueBuilder.durable(WORKFLOW_APPROVAL_EVENTS_DLQ).build();
    }

    @Bean
    public Binding workflowApprovalRequiredBinding() {
        return BindingBuilder.bind(workflowApprovalEventsQueue()).to(opsmindEventsExchange()).with(WORKFLOW_APPROVAL_REQUIRED_ROUTING_KEY);
    }

    @Bean
    public Binding workflowApprovalEventsDlqBinding() {
        return BindingBuilder.bind(workflowApprovalEventsDlq()).to(opsmindDeadLetterExchange()).with(WORKFLOW_APPROVAL_EVENTS_DLQ);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory workflowApprovalEventsListenerContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    /** SPEC-PG-027: {@code ticket.approval.required.v1} — mirrors {@link #toolApprovalEventsQueue} exactly, see its own javadoc. */
    @Bean
    public Queue ticketApprovalEventsQueue() {
        return QueueBuilder.durable(TICKET_APPROVAL_EVENTS_QUEUE)
            .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", TICKET_APPROVAL_EVENTS_DLQ)
            .build();
    }

    @Bean
    public Queue ticketApprovalEventsDlq() {
        return QueueBuilder.durable(TICKET_APPROVAL_EVENTS_DLQ).build();
    }

    @Bean
    public Binding ticketApprovalRequiredBinding() {
        return BindingBuilder.bind(ticketApprovalEventsQueue()).to(opsmindEventsExchange()).with(TICKET_APPROVAL_REQUIRED_ROUTING_KEY);
    }

    @Bean
    public Binding ticketApprovalEventsDlqBinding() {
        return BindingBuilder.bind(ticketApprovalEventsDlq()).to(opsmindDeadLetterExchange()).with(TICKET_APPROVAL_EVENTS_DLQ);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory ticketApprovalEventsListenerContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    /** SPEC-PG-028: {@code policy.evaluation.requested.v1} — mirrors {@link #toolApprovalEventsQueue} exactly, see its own javadoc. */
    @Bean
    public Queue policyEvaluationEventsQueue() {
        return QueueBuilder.durable(POLICY_EVALUATION_EVENTS_QUEUE)
            .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", POLICY_EVALUATION_EVENTS_DLQ)
            .build();
    }

    @Bean
    public Queue policyEvaluationEventsDlq() {
        return QueueBuilder.durable(POLICY_EVALUATION_EVENTS_DLQ).build();
    }

    @Bean
    public Binding policyEvaluationRequestedBinding() {
        return BindingBuilder.bind(policyEvaluationEventsQueue()).to(opsmindEventsExchange()).with(POLICY_EVALUATION_REQUESTED_ROUTING_KEY);
    }

    @Bean
    public Binding policyEvaluationEventsDlqBinding() {
        return BindingBuilder.bind(policyEvaluationEventsDlq()).to(opsmindDeadLetterExchange()).with(POLICY_EVALUATION_EVENTS_DLQ);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory policyEvaluationEventsListenerContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
