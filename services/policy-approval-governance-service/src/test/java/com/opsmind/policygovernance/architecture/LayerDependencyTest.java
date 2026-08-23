package com.opsmind.policygovernance.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Tag;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces the SPEC-PG-001 layering (13-package-and-class-design §Dependency
 * Direction) and, most importantly, INV-PG-001 ("06 Performs No Business
 * Side Effects"): nothing in this codebase may depend on another domain's
 * own service package, because that dependency is exactly how a governance
 * fact producer would end up directly executing a tool, mutating a ticket
 * or workflow, or writing memory content.
 */
@Tag("unit")
@AnalyzeClasses(packages = "com.opsmind.policygovernance", importOptions = ImportOption.DoNotIncludeTests.class)
class LayerDependencyTest {

    @ArchTest
    static final ArchRule domainMustNotDependOnSpring =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule domainMustNotDependOnJpa =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("jakarta.persistence..", "org.hibernate..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule domainMustNotDependOnRabbit =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.amqp..", "com.rabbitmq..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule applicationMustNotDependOnInfrastructure =
        noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule applicationMustNotDependOnApi =
        noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..api..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule apiMustNotAccessPersistenceRepository =
        noClasses().that().resideInAPackage("..api..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure.persistence..")
            .allowEmptyShould(true);

    /**
     * INV-PG-001. No sibling-domain service package name may appear anywhere in
     * this codebase — 06 must reach other domains only through the governance
     * facts it publishes (policy decisions, approval events), never by calling
     * into their execution/state-mutation code directly.
     */
    @ArchTest
    static final ArchRule mustNotDependOnOtherDomainServices =
        noClasses().should().dependOnClassesThat().resideInAnyPackage(
            "..ticketworkflow..", "..toolgateway..", "..tool_gateway..",
            "..agentruntime..", "..memoryknowledge.."
        ).allowEmptyShould(true);
}
