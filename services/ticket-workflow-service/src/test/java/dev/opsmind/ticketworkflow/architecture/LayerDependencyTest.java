package dev.opsmind.ticketworkflow.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Tag;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@Tag("unit")
@AnalyzeClasses(packages = "dev.opsmind.ticketworkflow", importOptions = ImportOption.DoNotIncludeTests.class)
class LayerDependencyTest {

    @ArchTest
    static final ArchRule domainMustNotDependOnSpring =
        noClasses().that().resideInAPackage("..ticket.domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule domainMustNotDependOnJpa =
        noClasses().that().resideInAPackage("..ticket.domain..")
            .should().dependOnClassesThat().resideInAnyPackage("jakarta.persistence..", "org.hibernate..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule applicationMustNotDependOnInfrastructure =
        noClasses().that().resideInAPackage("..ticket.application..")
            .should().dependOnClassesThat().resideInAPackage("..ticket.infrastructure..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule apiMustNotAccessPersistenceRepository =
        noClasses().that().resideInAPackage("..ticket.api..")
            .should().dependOnClassesThat().resideInAPackage("..ticket.infrastructure.persistence..")
            .allowEmptyShould(true);
}
