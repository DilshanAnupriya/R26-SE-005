package com.sprintiq.pms.seed;

import com.sprintiq.pms.modules.backlog.application.EpicService;
import com.sprintiq.pms.modules.backlog.application.StoryService;
import com.sprintiq.pms.modules.backlog.domain.StoryStatus;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.*;
import com.sprintiq.pms.modules.documents.application.DocumentService;
import com.sprintiq.pms.modules.documents.domain.DocumentKind;
import com.sprintiq.pms.modules.documents.domain.DocumentStatus;
import com.sprintiq.pms.modules.documents.web.dto.DocumentDtos.*;
import com.sprintiq.pms.modules.goals.application.GoalService;
import com.sprintiq.pms.modules.goals.domain.MetricDirection;
import com.sprintiq.pms.modules.goals.domain.MetricType;
import com.sprintiq.pms.modules.goals.web.dto.GoalDtos.*;
import com.sprintiq.pms.modules.organizations.application.OrganizationProvisioningService;
import com.sprintiq.pms.modules.organizations.domain.*;
import com.sprintiq.pms.modules.projects.application.MilestoneService;
import com.sprintiq.pms.modules.projects.application.ProjectService;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.*;
import com.sprintiq.pms.modules.roadmap.application.RoadmapService;
import com.sprintiq.pms.modules.roadmap.domain.Confidence;
import com.sprintiq.pms.modules.roadmap.web.dto.RoadmapDtos.*;
import com.sprintiq.pms.modules.sprints.application.SprintService;
import com.sprintiq.pms.modules.sprints.web.dto.SprintDtos.*;
import com.sprintiq.pms.modules.users.domain.User;
import com.sprintiq.pms.modules.users.domain.UserRepository;
import com.sprintiq.pms.modules.users.domain.UserStatus;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

import static com.sprintiq.pms.common.domain.EntityType.EPIC;

/**
 * A worked example of the product, for looking at and testing against.
 *
 * <p>Everything is created through the application services rather than by inserting
 * rows. A seeder that writes straight to the database can produce states the product
 * itself cannot reach — a sprint with no board columns, a story whose status disagrees
 * with its workflow state — and then the first bug report is about the fixture.</p>
 *
 * <p>Off by default and refused entirely in production. Turn it on with
 * {@code SPRINTIQ_SEED_ENABLED=true} or {@code --sprintiq.seed.enabled=true}.</p>
 *
 * <p>Dates are relative to the day it runs, so the demo is never stale: the active
 * sprint is always mid-flight, one initiative is always behind pace, and the burndown
 * always has a completed sprint behind it.</p>
 */
@Slf4j
@Component
@Profile("!prod")
@ConditionalOnProperty(name = "sprintiq.seed.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DemoDataSeeder implements ApplicationRunner {

    public static final String DEMO_EMAIL = "demo@sprintiq.test";
    public static final String DEMO_PASSWORD = "Str0ng!Passw0rd#2026";

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final OrgMemberRepository orgMemberRepository;
    private final RoleRepository roleRepository;
    private final WorkspaceRepository workspaceRepository;
    private final OrganizationProvisioningService provisioningService;
    private final PasswordEncoder passwordEncoder;

    private final ProjectService projectService;
    private final MilestoneService milestoneService;
    private final EpicService epicService;
    private final StoryService storyService;
    private final SprintService sprintService;
    private final GoalService goalService;
    private final RoadmapService roadmapService;
    private final DocumentService documentService;

    /** Monday of the current week — every other date is relative to it. */
    private final LocalDate monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

    /**
     * Deliberately not {@code @Transactional}.
     *
     * <p>Each service call carries its own boundary and commits as it goes. Wrapping
     * the lot in one breaks it twice over: the asynchronous audit writer runs on
     * another thread and cannot see rows this transaction has not committed, and
     * Hibernate's flush ordering happily inserts a story pointing at a sprint whose
     * own insert has not gone out yet.</p>
     *
     * <p>The upside is that a failure halfway leaves what it managed, which is what
     * you want from a fixture: you can see how far it got.</p>
     */
    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        if (userRepository.existsByEmailNormalized(User.normalizeEmail(DEMO_EMAIL))) {
            log.info("Demo data already present — nothing to seed");
            return;
        }

        UUID ownerId = createUser(DEMO_EMAIL, "Dev Patel");
        Organization organization = provisioningService.provisionOrganization("Acme Robotics", ownerId);
        enrol(organization.getId(), ownerId, Role.OWNER);

        // Extra people so assignees, avatars and the workload board have something to
        // show. They share the demo password; this never runs anywhere real.
        UUID nadia = createUser("nadia@sprintiq.test", "Nadia Khan");
        UUID rui = createUser("rui@sprintiq.test", "Rui Santos");
        UUID amara = createUser("amara@sprintiq.test", "Amara Mensah");
        for (UUID member : List.of(nadia, rui, amara)) {
            enrol(organization.getId(), member, Role.MEMBER);
        }

        // A non-system context, so the seeder goes through the same tenant filter every
        // request does. systemContext(true) would switch it off and let a mistake here
        // write rows no request could ever have produced.
        TenantContext.set(TenantContext.Context.builder()
                .organizationId(organization.getId())
                .userId(ownerId)
                .permissions(Set.of())
                .systemContext(false)
                .build());
        try {
            UUID workspaceId = workspaceRepository
                    .findByOrganizationIdAndDeletedAtIsNull(organization.getId()).get(0).getId();
            seedEverything(workspaceId, ownerId, nadia, rui, amara);
        } finally {
            TenantContext.clear();
        }

        log.info("Seeded demo data — sign in as {} / {}", DEMO_EMAIL, DEMO_PASSWORD);
    }

    // ------------------------------------------------------------------ the walk

    private void seedEverything(UUID workspaceId, UUID owner, UUID nadia, UUID rui, UUID amara) {
        // Domain and tech stack use the vocabulary the model was trained on; anything
        // else it simply does not recognise and leaves the one-hot unset.
        ProjectResponse payroll = project(workspaceId, "Payroll SaaS", "PAY",
                "finance", "springboot", "senior", 6, 24, 0.88, owner);
        ProjectResponse crm = project(workspaceId, "CRM Platform", "CRM",
                "ecommerce", "nodejs", "mid", 5, 18, 0.74, nadia);
        // Left without estimation context on purpose, so the fallback path — training
        // medians, and an explanation that says so — is visible in the demo too.
        ProjectResponse mobile = project(workspaceId, "Mobile App", "MOB",
                "logistics", "react", null, 4, 14, 0.80, rui);

        // ---- Payroll: the project with a full history behind it -----------------
        UUID sso = epic(payroll.id(), "SSO and authentication", monday.minusDays(28), monday.plusDays(21));
        UUID currency = epic(payroll.id(), "Multi-currency payouts", monday.plusDays(21), monday.plusDays(84));

        UUID google = story(payroll.id(), sso, "Google Workspace SSO", 8, nadia, null);
        UUID entra = story(payroll.id(), sso, "Microsoft Entra SSO", 8, nadia, null);
        UUID scim = story(payroll.id(), sso, "SCIM user provisioning", 5, rui, null);
        UUID enforce = story(payroll.id(), sso, "Enforce SSO per organization", 5, nadia, null);
        story(payroll.id(), currency, "FX rate provider integration", 13, rui, monday.plusDays(35));
        story(payroll.id(), currency, "Per-country payout rules", 8, null, null);

        // A completed sprint, so velocity and burndown have a history rather than a
        // single point — a chart with one reading tells you nothing about a trend.
        SprintResponse past = sprint(payroll.id(), "Sprint 23", monday.minusDays(21), monday.minusDays(7));
        sprintService.addItems(past.id(), new AddSprintItemsRequest(List.of(google, entra)));
        sprintService.start(past.id());
        complete(google);
        complete(entra);
        // Anything unfinished goes back to the backlog rather than being dragged forward,
        // which is the choice most teams make and the one that keeps the next sprint honest.
        sprintService.complete(past.id(), new CompleteSprintRequest(
                SpilloverStrategy.BACKLOG, null, "Both SSO providers landed; SCIM slipped."));

        SprintResponse current = sprint(payroll.id(), "Sprint 24", monday.minusDays(7), monday.plusDays(7));
        sprintService.addItems(current.id(), new AddSprintItemsRequest(List.of(scim, enforce)));
        sprintService.start(current.id());
        complete(scim);
        // One blocked item, because a board where nothing is ever stuck is not a board
        // anybody recognises.
        storyService.block(enforce, new BlockStoryRequest("Waiting on the security review"));

        milestoneService.create(payroll.id(), new CreateMilestoneRequest(
                "Payroll v2 GA", "First enterprise cohort", monday.plusDays(42), null));

        // ---- CRM: the one that is behind -----------------------------------------
        UUID leadScoring = epic(crm.id(), "Lead scoring engine", monday.minusDays(42), monday.plusDays(14));
        story(crm.id(), leadScoring, "Scoring model service", 13, amara, null);
        story(crm.id(), leadScoring, "Signal ingestion", 8, amara, monday.plusDays(7));
        complete(story(crm.id(), leadScoring, "Score display on the contact card", 3, nadia, null));

        // ---- Mobile: barely started, and deliberately undated in places ----------
        UUID shell = epic(mobile.id(), "App shell and navigation", null, null);
        complete(story(mobile.id(), shell, "Tab navigation", 5, rui, null));
        story(mobile.id(), shell, "Deep linking", 5, rui, monday.plusDays(28));
        story(mobile.id(), shell, "Offline shell caching", 8, null, null);

        seedRoadmap(payroll, crm, mobile, sso, currency, leadScoring, shell, owner, nadia, rui);
        seedGoals(owner, nadia);
        seedDocuments();
    }

    private void seedRoadmap(ProjectResponse payroll, ProjectResponse crm, ProjectResponse mobile,
                             UUID sso, UUID currency, UUID leadScoring, UUID shell,
                             UUID owner, UUID nadia, UUID rui) {
        initiative("Enterprise SSO", payroll.id(), monday.minusDays(40), monday.plusDays(50),
                Confidence.HIGH, owner, sso);
        initiative("Multi-currency payouts", payroll.id(), monday.plusDays(21), monday.plusDays(112),
                Confidence.MEDIUM, rui, currency);
        // Behind pace on purpose: a roadmap where everything is green teaches nobody to
        // look at the colour.
        initiative("Lead scoring engine", crm.id(), monday.minusDays(60), monday.plusDays(14),
                Confidence.LOW, nadia, leadScoring);
        initiative("Mobile app v1", mobile.id(), monday.minusDays(14), monday.plusDays(90),
                Confidence.MEDIUM, rui, shell);
        // Cross-project, and with nothing linked — the case that must stay PLANNED
        // however late it gets, because there is no work to be behind on.
        initiative("SOC 2 Type II", null, monday, monday.plusDays(200),
                Confidence.MEDIUM, owner, null);
    }

    private void seedGoals(UUID owner, UUID nadia) {
        String period = "%d-Q%d".formatted(monday.getYear(), (monday.getMonthValue() - 1) / 3 + 1);

        GoalResponse onboarding = goalService.create(new CreateGoalRequest(
                "Make onboarding something new customers finish",
                "Activation is the first number that predicts retention.",
                period, monday.minusDays(30), monday.plusDays(60), null, null, owner, null, null, null,
                List.of(
                        new CreateKeyResultRequest("Median time to first project", MetricType.NUMBER,
                                "min", BigDecimal.valueOf(42), BigDecimal.valueOf(10),
                                MetricDirection.DECREASE, owner),
                        new CreateKeyResultRequest("Activation rate", MetricType.PERCENT, null,
                                BigDecimal.valueOf(31), BigDecimal.valueOf(60),
                                MetricDirection.INCREASE, nadia))));

        // Check-ins, so the history panel and the trend arrows have something real.
        UUID timeToFirstProject = onboarding.keyResults().get(0).id();
        UUID activation = onboarding.keyResults().get(1).id();
        goalService.checkIn(timeToFirstProject, new CheckinRequest(
                BigDecimal.valueOf(26), (short) 4, "Guided setup shipped to half of new signups"));
        goalService.checkIn(timeToFirstProject, new CheckinRequest(
                BigDecimal.valueOf(18), (short) 4, "Template projects removed the blank first screen"));
        goalService.checkIn(activation, new CheckinRequest(
                BigDecimal.valueOf(47), (short) 3, "Activation up, though weekend cohorts still lag"));

        // A goal nobody has touched, so the off-track path is visible too.
        goalService.create(new CreateGoalRequest(
                "Cut support load per customer", null, period,
                monday.minusDays(70), monday.plusDays(10), null, null, nadia, null, null, null,
                List.of(new CreateKeyResultRequest("Tickets per customer per month",
                        MetricType.NUMBER, null, BigDecimal.valueOf(4.2), BigDecimal.valueOf(2),
                        MetricDirection.DECREASE, nadia))));
    }

    private void seedDocuments() {
        UUID engineering = folder("Engineering", null);
        UUID runbooks = folder("Runbooks", engineering);
        UUID product = folder("Product", null);

        page(runbooks, "Incident runbook", """
                # Incident runbook

                ## Declaring
                Anyone can declare. Being wrong about an incident costs a meeting; being
                late costs customers.

                ## Roles
                - **Incident lead** — decides and delegates, does not fix
                - **Comms** — writes the status page, not the fix
                - **Fixers** — everyone else

                ## Afterwards
                Postmortem within two working days, blameless, written while it is fresh.
                """);

        page(engineering, "On-call handbook", """
                # On-call

                One week, handover on Monday. If you cannot take a shift, swap it before
                the rota publishes rather than after — a silent gap is worse than a late
                swap.
                """);

        UUID ssoPlan = page(product, "SSO rollout plan", """
                # Enterprise SSO

                ## Why now
                Three of the last five enterprise deals asked for it before signature.

                ## Scope
                Google Workspace and Microsoft Entra. SAML is not in this phase; if it
                blocks a deal we will revisit rather than pre-build it.
                """);

        // An edit with a note, so the version history is not a single row.
        documentService.update(ssoPlan, new UpdateDocumentRequest(null, """
                # Enterprise SSO

                ## Why now
                Three of the last five enterprise deals asked for it before signature.

                ## Scope
                Google Workspace and Microsoft Entra. SAML is not in this phase; if it
                blocks a deal we will revisit rather than pre-build it.

                ## Not doing
                SCIM deprovisioning is a fast follow, not part of GA.
                """, null, null, null, "Ruled SCIM deprovisioning out of GA"));

        // A draft, so the tree shows both states.
        documentService.create(new CreateDocumentRequest("Multi-currency notes",
                DocumentKind.DOCUMENT, product, null, """
                # Multi-currency payouts

                Draft. Open question: do we hold FX at run time or at approval time? The
                answer changes what we owe when a rate moves between the two.
                """, DocumentStatus.DRAFT));
    }

    // ------------------------------------------------------------------ helpers

    private UUID createUser(String email, String fullName) {
        User user = User.builder()
                .fullName(fullName)
                .passwordHash(passwordEncoder.encode(DEMO_PASSWORD))
                .status(UserStatus.ACTIVE)
                .timezone("UTC")
                .emailVerifiedAt(Instant.now())
                .build();
        user.setEmail(email);
        return userRepository.save(user).getId();
    }

    private void enrol(UUID organizationId, UUID userId, String roleCode) {
        Role role = roleRepository.findByCodeAndSystemRoleTrue(roleCode)
                .orElseThrow(() -> new IllegalStateException(
                        "System role %s is missing — check migration V5".formatted(roleCode)));

        OrgMember membership = OrgMember.builder()
                .userId(userId)
                .roleId(role.getId())
                .status(MembershipStatus.ACTIVE)
                .joinedAt(Instant.now())
                .build();
        membership.setOrganizationId(organizationId);
        orgMemberRepository.save(membership);
    }

    private ProjectResponse project(UUID workspaceId, String name, String key, String domain,
                                    String techStack, String experience, int teamSize,
                                    double velocity, double completionRate, UUID lead) {
        return projectService.create(new CreateProjectRequest(workspaceId, name, key,
                "Seeded demo project", monday.minusDays(60), monday.plusDays(120),
                BigDecimal.valueOf(250_000), "USD", lead, null, domain,
                // Primary stack first: the model is given one stack, and it takes the
                // first entry.
                List.of(techStack, "typescript"), (short) 14, List.of(), List.of(),
                (short) teamSize, experience, BigDecimal.valueOf(velocity),
                BigDecimal.valueOf(completionRate)));
    }

    private UUID epic(UUID projectId, String title, LocalDate start, LocalDate target) {
        return epicService.create(new CreateEpicRequest(projectId, title, null, null, null, null,
                null, start, target)).id();
    }

    private UUID story(UUID projectId, UUID epicId, String title, int points, UUID assignee,
                       LocalDate dueDate) {
        StoryResponse story = storyService.create(new CreateStoryRequest(projectId, epicId, null,
                title, null, null, null, null, null, BigDecimal.valueOf(points), null, null,
                assignee, dueDate, null, null, null));
        return story.id();
    }

    /** Through the transition endpoint, so the board column moves with the status. */
    private void complete(UUID storyId) {
        storyService.changeStatus(storyId, new UpdateStoryStatusRequest(StoryStatus.DONE, null));
    }

    private SprintResponse sprint(UUID projectId, String name, LocalDate start, LocalDate end) {
        return sprintService.create(new CreateSprintRequest(projectId, name,
                "Ship what we committed to", start, end, null, null, null, null));
    }

    private void initiative(String title, UUID projectId, LocalDate start, LocalDate target,
                            Confidence confidence, UUID owner, UUID epicId) {
        roadmapService.create(new CreateInitiativeRequest(title, null, projectId, start, target,
                null, confidence, owner,
                epicId == null ? List.of() : List.of(new LinkRequest(EPIC, epicId))));
    }

    private UUID folder(String title, UUID parentId) {
        return documentService.create(new CreateDocumentRequest(title, DocumentKind.FOLDER,
                parentId, null, null, null)).id();
    }

    private UUID page(UUID parentId, String title, String content) {
        return documentService.create(new CreateDocumentRequest(title, DocumentKind.DOCUMENT,
                parentId, null, content, DocumentStatus.PUBLISHED)).id();
    }
}
