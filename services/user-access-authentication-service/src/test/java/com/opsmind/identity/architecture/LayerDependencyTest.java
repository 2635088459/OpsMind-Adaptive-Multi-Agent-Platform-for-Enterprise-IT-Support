package com.opsmind.identity.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Tag;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces 13-package-and-class-design §Dependency Direction ("Domain is
 * pure Java with no Spring/JPA/JWT/HTTP dependency. Application depends
 * only on domain and ports; adapters implement ports; controllers never
 * access repositories") and 02-business-invariants' cross-domain boundary:
 * domain 01 must reach other domains only through the trusted identity/
 * authorization facts it emits, never by calling into their execution/
 * state-mutation code directly.
 */
@Tag("unit")
@AnalyzeClasses(packages = "com.opsmind.identity", importOptions = ImportOption.DoNotIncludeTests.class)
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
    static final ArchRule domainMustNotDependOnAmqp =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.amqp..", "com.rabbitmq..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule domainMustNotDependOnJwtOrHttp =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("jakarta.servlet..", "org.springframework.security..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule applicationMustDependOnlyOnDomainAndPorts =
        noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..", "..api..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule controllersMustNotAccessRepositories =
        noClasses().that().resideInAPackage("..api..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .allowEmptyShould(true);

    /**
     * 02-business-invariants §Cross-Domain Boundaries. No sibling-domain
     * service package name may appear anywhere in this codebase — domain 01
     * must reach other domains only through the trusted identity/
     * authorization facts it emits, never by calling into their execution/
     * state-mutation code directly.
     */
    @ArchTest
    static final ArchRule mustNotDependOnOtherDomainServices =
        noClasses().should().dependOnClassesThat().resideInAnyPackage(
            "..ticketworkflow..", "..toolgateway..", "..tool_gateway..",
            "..agentruntime..", "..memoryknowledge..", "..policygovernance.."
        ).allowEmptyShould(true);
}
