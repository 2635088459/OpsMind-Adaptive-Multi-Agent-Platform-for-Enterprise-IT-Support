package com.opsmind.policygovernance.infrastructure.evaluator;

import com.opsmind.policygovernance.application.port.RuleEvaluatorPort.Input;
import com.opsmind.policygovernance.domain.policy.PolicyRule;
import com.opsmind.policygovernance.domain.policy.RuleCondition;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * SPEC-PG-007: interprets a {@link PolicyRule}'s {@link RuleCondition} list
 * against the evaluator's request facts ({@link Input}) — the condition
 * matching engine 04-use-cases §UC-PG-001 step 3 and RuleCondition's own
 * javadoc both name as SPEC-PG-007's job.
 *
 * <p>A rule matches when every condition matches (implicit AND, per {@link
 * PolicyRule}'s own javadoc); an empty condition list always matches
 * ("catch-all" rule).
 *
 * <p><b>Fails closed by design, not by an explicit check here:</b> {@code
 * attribute} is resolved only against {@link Input}'s own fields (the only
 * facts the evaluator has); an unknown attribute, a condition referencing a
 * field the request left {@code null} (e.g. {@code resourceType} on a
 * non-resource action), or a numeric operator ({@code
 * GREATER_THAN_OR_EQUAL}/{@code LESS_THAN_OR_EQUAL}) against a
 * non-numeric value all throw a {@link RuntimeException} rather than
 * silently resolving to "no match". That exception is deliberately left
 * uncaught here — {@code PolicyDecisionService#evaluate}'s own try/catch
 * (SPEC-PG-006) already converts any evaluator {@link RuntimeException}
 * into the fail-safe {@code DENY}/{@code evaluationFailed=true} snapshot
 * (10-failure-handling §Policy Evaluation Failure: "if rule parsing fails
 * ... return EVALUATION_FAILED ... do not default allow"), so duplicating
 * that fail-closed handling here would only obscure which layer owns it.
 */
final class RuleConditionMatcher {

    private RuleConditionMatcher() {
    }

    static boolean matches(PolicyRule rule, Input input) {
        for (RuleCondition condition : rule.conditions()) {
            if (!matches(condition, input)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(RuleCondition condition, Input input) {
        String actual = resolve(condition.attribute(), input);
        String expected = condition.value();
        return switch (condition.operator()) {
            case EQUALS -> actual.equals(expected);
            case NOT_EQUALS -> !actual.equals(expected);
            case IN -> Arrays.stream(expected.split(",")).map(String::trim).anyMatch(actual::equals);
            case NOT_IN -> Arrays.stream(expected.split(",")).map(String::trim).noneMatch(actual::equals);
            case GREATER_THAN_OR_EQUAL -> Double.parseDouble(actual) >= Double.parseDouble(expected);
            case LESS_THAN_OR_EQUAL -> Double.parseDouble(actual) <= Double.parseDouble(expected);
            case MATCHES -> Pattern.matches(expected, actual);
        };
    }

    /**
     * {@code attribute} is an opaque dot-path per {@link RuleCondition}'s own
     * javadoc; the evaluator's only known facts are {@link Input}'s six
     * request fields, so those are the only paths resolved. {@code
     * inputHash} is included for completeness even though matching a rule
     * on it would be unusual.
     */
    private static String resolve(String attribute, Input input) {
        return switch (attribute) {
            case "subjectType" -> input.subjectType();
            case "subjectId" -> input.subjectId();
            case "actionType" -> input.actionType();
            case "resourceType" -> input.resourceType();
            case "resourceId" -> input.resourceId();
            case "tenantId" -> input.tenantId();
            case "inputHash" -> input.inputHash();
            default -> throw new IllegalStateException("unknown rule condition attribute: " + attribute);
        };
    }
}
