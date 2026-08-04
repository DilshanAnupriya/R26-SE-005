package com.sprintiq.pms.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Executable architecture. These rules are what keeps the modular monolith
 * decomposable later — once a module reaches into another module's internals,
 * extracting it into a service stops being a refactor and becomes a rewrite.
 */
@DisplayName("Architecture rules")
class LayeringRulesTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.sprintiq.pms");
    }

    @Test
    @DisplayName("controllers must not touch repositories directly")
    void controllersDoNotUseRepositories() {
        ArchRule rule = noClasses().that().resideInAPackage("..web..")
                .and().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                .because("controllers delegate to application services; business logic never lives in the web layer");
        rule.check(classes);
    }

    @Test
    @DisplayName("domain layer must not depend on web or Spring MVC")
    void domainIsFrameworkAgnostic() {
        ArchRule rule = noClasses().that().resideInAPackage("..modules..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("..web..", "org.springframework.web..");
        rule.check(classes);
    }

    @Test
    @DisplayName("entities must not be returned from controllers")
    void controllersReturnDtos() {
        ArchRule rule = noClasses().that().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat().areAnnotatedWith(jakarta.persistence.Entity.class)
                .because("entities are mapped to DTOs so the persistence model can evolve independently");
        rule.check(classes);
    }

    @Test
    @DisplayName("every tenant-scoped entity extends TenantAwareEntity")
    void tenantEntitiesAreScoped() {
        ArchRule rule = classes().that().areAnnotatedWith(jakarta.persistence.Entity.class)
                .and().resideInAPackage("..modules..domain..")
                .and().haveSimpleNameNotEndingWith("Log")
                .and().haveSimpleNameNotStartingWith("User")
                .and().haveSimpleNameNotStartingWith("Organization")
                .and().haveSimpleNameNotStartingWith("Permission")
                .and().haveSimpleNameNotStartingWith("Role")
                .and().haveSimpleNameNotStartingWith("RefreshToken")
                .and().haveSimpleNameNotStartingWith("OneTimeToken")
                .should().beAssignableTo(com.sprintiq.pms.common.domain.TenantAwareEntity.class)
                .because("anything else would be queryable across tenants");
        rule.allowEmptyShould(true).check(classes);
    }

    @Test
    @DisplayName("services are annotated and transactional boundaries live in the application layer")
    void servicesLiveInApplicationLayer() {
        ArchRule rule = classes().that().haveSimpleNameEndingWith("Service")
                .and().resideInAPackage("..modules..")
                .should().resideInAPackage("..application..");
        rule.check(classes);
    }
}
