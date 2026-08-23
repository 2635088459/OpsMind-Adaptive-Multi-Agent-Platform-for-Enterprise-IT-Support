package com.opsmind.policygovernance.domain.policy;

import java.util.Objects;

/**
 * One structural predicate inside a {@link PolicyRule} (01-domain-model
 * §PolicyRule: "Rule is an evaluable unit ... expressing condition,
 * effect, risk, approval requirement, and constraints"). A rule's {@code
 * conditions} list is implicitly AND-combined, mirroring how {@code
 * PolicyRule.constraints}/{@code PolicyDecision.reasonCodes} are already
 * modeled as flat lists rather than an expression tree.
 *
 * <p>This is the structural half only — SPEC-PG-004's own LLD mapping is
 * 01-domain-model, not the evaluator. {@code attribute} is an opaque
 * dot-path into the evaluator's input facts (e.g. {@code "subjectType"},
 * {@code "resourceType"}) and interpreting {@code operator} against a real
 * input value — including what "matches" means for {@link
 * Operator#MATCHES} — is SPEC-PG-007's job (Rule Evaluator And Risk
 * Mapping, 04-use-cases/10-failure-handling); until then {@code
 * infrastructure.evaluator.DefaultRuleEvaluatorAdapter} carries this data
 * without interpreting it.
 */
public record RuleCondition(String attribute, Operator operator, String value) {

    public RuleCondition {
        Objects.requireNonNull(attribute, "attribute");
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(value, "value");
    }

    public enum Operator {
        EQUALS,
        NOT_EQUALS,
        IN,
        NOT_IN,
        GREATER_THAN_OR_EQUAL,
        LESS_THAN_OR_EQUAL,
        MATCHES
    }
}
