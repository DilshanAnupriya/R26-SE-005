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

    /**
     * Every entity is either tenant-scoped or explicitly declared as shared reference
     * data.
     *
     * <p>The exemption is an annotation on the class rather than a list of name prefixes
     * in this test. A prefix list drifts: someone adds {@code AiModel}, the rule fails,
     * and the quickest fix is to widen the list — which also silently exempts the next
     * five classes starting with "Ai". {@code @GlobalReferenceEntity} makes the author
     * state the reason at the point of the decision, where a reviewer sees it.</p>
     */
    @Test
    @DisplayName("every entity is tenant-scoped or explicitly declared global")
    void tenantEntitiesAreScoped() {
        ArchRule rule = classes().that().areAnnotatedWith(jakarta.persistence.Entity.class)
                .and().resideInAPackage("..modules..domain..")
                .and().areNotAnnotatedWith(com.sprintiq.pms.common.domain.GlobalReferenceEntity.class)
                .and().haveSimpleNameNotEndingWith("Log")
                .and().haveSimpleNameNotStartingWith("User")
                .and().haveSimpleNameNotStartingWith("Organization")
                .and().haveSimpleNameNotStartingWith("Permission")
                .and().haveSimpleNameNotStartingWith("Role")
                .and().haveSimpleNameNotStartingWith("RefreshToken")
                .and().haveSimpleNameNotStartingWith("OneTimeToken")
                .should().implement(com.sprintiq.pms.common.domain.TenantScoped.class)
                .because("anything else would be queryable across tenants");
        rule.allowEmptyShould(true).check(classes);
    }

    /**
     * The AI module must not import the delivery modules.
     *
     * <p>It reaches them through ports it declares itself, implemented by the module that
     * owns the data. Without this rule the ports quietly become optional — one direct
     * {@code StoryRepository} injection is easier than adding a method to an interface,
     * and after three of them the AI layer is welded to the backlog schema.</p>
     */
    @Test
    @DisplayName("the AI module talks to delivery modules only through its own ports")
    void aiModuleDependsOnPortsOnly() {
        ArchRule rule = noClasses().that().resideInAPackage("..modules.ai..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..modules.backlog..", "..modules.sprints..", "..modules.tasks..",
                        "..modules.projects..", "..modules.users..")
                .because("feature assembly goes through StoryFeatureSource and friends, so the AI "
                        + "module stays extractable and the delivery schema stays free to change");
        rule.check(classes);
    }

    /**
     * The roadmap must not import the delivery modules.
     *
     * <p>It reads progress through {@code InitiativeWorkSource}, which the backlog
     * implements. Without this rule the port quietly becomes optional — injecting
     * {@code StoryRepository} is easier than adding a method to an interface, and after
     * two of those the roadmap is welded to the backlog schema and a link to a sprint
     * or a task needs a migration rather than a class.</p>
     *
     * <p>The projects and users modules are deliberately not on this list: their
     * application services are published for exactly this use, and every module
     * resolves a name or an avatar through them.</p>
     */
    @Test
    @DisplayName("the roadmap reaches delivery work only through its own port")
    void roadmapDependsOnPortsOnly() {
        ArchRule rule = noClasses().that().resideInAPackage("..modules.roadmap..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..modules.backlog..", "..modules.sprints..", "..modules.tasks..")
                .because("progress comes through InitiativeWorkSource, so linking a new kind of "
                        + "work is a class rather than a schema change");
        rule.check(classes);
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
