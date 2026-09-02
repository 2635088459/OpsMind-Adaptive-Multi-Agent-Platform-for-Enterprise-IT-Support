package com.opsmind.attachment.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Tag;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Mirrors every other Java service in this platform's own LayerDependencyTest exactly — same hexagonal layering rules, same "no sibling-domain package name anywhere" cross-domain-boundary rule. */
@Tag("unit")
@AnalyzeClasses(packages = "com.opsmind.attachment", importOptions = ImportOption.DoNotIncludeTests.class)
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
    static final ArchRule domainMustNotDependOnAws =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("software.amazon.awssdk..")
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
    static final ArchRule apiMustNotAccessPersistenceDirectly =
        noClasses().that().resideInAPackage("..api..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure.persistence..")
            .allowEmptyShould(true);

    /** No sibling-domain service package name may appear anywhere — this is a shared capability consumed BY other domains (09-employee-portal, 03-agent-runtime-orchestration), never one that reaches into their own execution/state-mutation code. */
    @ArchTest
    static final ArchRule mustNotDependOnOtherDomainServices =
        noClasses().should().dependOnClassesThat().resideInAnyPackage(
            "..ticketworkflow..", "..toolgateway..", "..tool_gateway..",
            "..agentruntime..", "..memoryknowledge..", "..policygovernance..", "..identity.."
        ).allowEmptyShould(true);
}
