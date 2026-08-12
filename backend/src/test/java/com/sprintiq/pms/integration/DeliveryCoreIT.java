package com.sprintiq.pms.integration;

import com.sprintiq.pms.common.error.BusinessRuleException;
import com.sprintiq.pms.common.error.ConflictException;
import com.sprintiq.pms.modules.backlog.application.BacklogService;
import com.sprintiq.pms.modules.backlog.application.DependencyService;
import com.sprintiq.pms.modules.backlog.application.EpicService;
import com.sprintiq.pms.modules.backlog.application.StoryService;
import com.sprintiq.pms.modules.backlog.domain.DependencyType;
import com.sprintiq.pms.modules.backlog.domain.StoryStatus;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.*;
import com.sprintiq.pms.modules.organizations.application.OrganizationProvisioningService;
import com.sprintiq.pms.modules.organizations.domain.Organization;
import com.sprintiq.pms.modules.organizations.domain.WorkspaceRepository;
import com.sprintiq.pms.modules.projects.application.ProjectService;
import com.sprintiq.pms.modules.projects.application.WorkflowStateService;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.*;
import com.sprintiq.pms.modules.sprints.application.SprintService;
import com.sprintiq.pms.modules.sprints.domain.SprintStatus;
import com.sprintiq.pms.modules.sprints.web.dto.SprintDtos.*;
import com.sprintiq.pms.modules.tasks.application.TaskService;
import com.sprintiq.pms.modules.tasks.domain.TaskStatus;
import com.sprintiq.pms.modules.tasks.web.dto.TaskDtos.*;
import com.sprintiq.pms.modules.users.domain.User;
import com.sprintiq.pms.modules.users.domain.UserRepository;
import com.sprintiq.pms.modules.users.domain.UserStatus;
import com.sprintiq.pms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static com.sprintiq.pms.common.domain.EntityType.STORY;
import static org.assertj.core.api.Assertions.*;

/**
 * End-to-end exercise of the Phase 2 delivery core against real Postgres:
 * project → workflow states → epic → story → task → sprint start/complete.
 *
 * <p>Runs through the application services rather than MockMvc so the assertions land on
 * the business rules themselves — commitment freezing, spillover, cycle detection,
 * rank ordering — instead of on HTTP plumbing.</p>
 */
@Transactional
class DeliveryCoreIT extends AbstractIntegrationTest {

    @Autowired OrganizationProvisioningService provisioningService;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired UserRepository userRepository;
    @Autowired ProjectService projectService;
    @Autowired WorkflowStateService workflowStateService;
    @Autowired EpicService epicService;
    @Autowired StoryService storyService;
    @Autowired BacklogService backlogService;
    @Autowired DependencyService dependencyService;
    @Autowired SprintService sprintService;
    @Autowired TaskService taskService;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * A real {@code users} row. {@code organizations.owner_user_id} is a foreign key, so
     * a randomly generated id is rejected by the database rather than quietly accepted.
     */
    private UUID newUser(String label) {
        String email = "%s+%s@example.test".formatted(label, UUID.randomUUID());
        User user = User.builder()
                .email(email)
                .emailNormalized(email.toLowerCase(Locale.ROOT))
                .fullName("Test %s".formatted(label))
                .status(UserStatus.ACTIVE)
                .emailVerifiedAt(java.time.Instant.now())
                .build();
        return userRepository.saveAndFlush(user).getId();
    }

    /** Provisions a tenant and binds it to the calling thread with full permissions. */
    private ProjectResponse newProject(String orgName, String projectName) {
        UUID userId = newUser("owner");
        Organization organization = provisioningService.provisionOrganization(orgName, userId);

        // systemContext stays false: it switches the Hibernate tenant filter off (it exists
        // for schedulers and seeders), and with it off deliveryRowsAreTenantIsolated would
        // pass no matter what the isolation code did. Permissions can be empty because
        // @PreAuthorize lives on the controllers, and these tests drive the services.
        TenantContext.set(TenantContext.Context.builder()
                .organizationId(organization.getId())
                .userId(userId)
                .permissions(Set.of())
                .systemContext(false)
                .build());

        UUID workspaceId = workspaceRepository
                .findByOrganizationIdAndDeletedAtIsNull(organization.getId())
                .get(0).getId();

        return projectService.create(new CreateProjectRequest(workspaceId, projectName, null,
                "Created by DeliveryCoreIT", null, null, null, "USD", null, null, "fintech",
                List.of("java", "react"), (short) 14, List.of(), List.of(), null, null, null, null));
    }

    private StoryResponse newStory(UUID projectId, String title, BigDecimal points) {
        return storyService.create(new CreateStoryRequest(projectId, null, null, title, null, null,
                null, null, null, points, null, null, null, null, null, null, null));
    }

    // ------------------------------------------------------------------ the walk

    @Test
    @DisplayName("project creation seeds board columns, an item key prefix and the creator as a member")
    void createProjectSeedsWorkflowAndMembership() {
        ProjectResponse project = newProject("Acme Robotics", "Mobile Banking App");

        assertThat(project.projectKey()).isEqualTo("MBA");
        assertThat(project.memberCount()).isEqualTo(1);

        List<WorkflowStateResponse> states = workflowStateService.list(project.id());
        assertThat(states).extracting(WorkflowStateResponse::code)
                .containsExactly("TODO", "IN_PROGRESS", "IN_REVIEW", "DONE");
    }

    @Test
    @DisplayName("items get sequential, project-prefixed keys and land in the first board column")
    void itemsGetHumanReadableKeys() {
        ProjectResponse project = newProject("Acme Robotics", "Payments Gateway");

        EpicResponse epic = epicService.create(new CreateEpicRequest(project.id(), "Card payments",
                null, null, null, null, null, null, null));
        StoryResponse first = newStory(project.id(), "Accept Visa", BigDecimal.valueOf(5));
        StoryResponse second = newStory(project.id(), "Accept Mastercard", BigDecimal.valueOf(3));

        assertThat(epic.itemKey()).isEqualTo("PG-1");
        assertThat(first.itemKey()).isEqualTo("PG-2");
        assertThat(second.itemKey()).isEqualTo("PG-3");
        assertThat(first.workflowStateId()).isNotNull();
        assertThat(first.estimateSource().name()).isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("backlog ranking reorders without touching untouched rows")
    void backlogRankingPlacesItemBetweenNeighbours() {
        ProjectResponse project = newProject("Acme Robotics", "Ranking Demo");

        StoryResponse a = newStory(project.id(), "A", null);
        StoryResponse b = newStory(project.id(), "B", null);
        StoryResponse c = newStory(project.id(), "C", null);
        assertThat(a.rank()).isLessThan(b.rank());
        assertThat(b.rank()).isLessThan(c.rank());

        // Move C between A and B.
        List<RankedItemResponse> ranked = backlogService.rank(project.id(),
                new RankRequest(List.of(c.id()), a.id(), b.id()));

        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).rank()).isGreaterThan(a.rank()).isLessThan(b.rank());
        // A and B keep the ranks they were created with — only the moved row is rewritten.
        assertThat(storyService.get(a.id()).rank()).isEqualTo(a.rank());
        assertThat(storyService.get(b.id()).rank()).isEqualTo(b.rank());
    }

    @Test
    @DisplayName("a blocking dependency that would close a cycle is refused")
    void dependencyCycleIsRejected() {
        ProjectResponse project = newProject("Acme Robotics", "Cycle Guard");

        StoryResponse a = newStory(project.id(), "A", null);
        StoryResponse b = newStory(project.id(), "B", null);
        StoryResponse c = newStory(project.id(), "C", null);

        dependencyService.create(STORY, a.id(),
                new CreateDependencyRequest(STORY, b.id(), DependencyType.BLOCKS));
        dependencyService.create(STORY, b.id(),
                new CreateDependencyRequest(STORY, c.id(), DependencyType.BLOCKS));

        // C blocks A would close A -> B -> C -> A.
        assertThatThrownBy(() -> dependencyService.create(STORY, c.id(),
                new CreateDependencyRequest(STORY, a.id(), DependencyType.BLOCKS)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    @DisplayName("BLOCKED_BY is stored inverted, so cycle detection sees it from either end")
    void inverseDependencyIsNormalised() {
        ProjectResponse project = newProject("Acme Robotics", "Inverse Guard");

        StoryResponse a = newStory(project.id(), "A", null);
        StoryResponse b = newStory(project.id(), "B", null);

        dependencyService.create(STORY, a.id(),
                new CreateDependencyRequest(STORY, b.id(), DependencyType.BLOCKS));

        // "A is blocked by B" is the same edge as "B blocks A" — and B blocks A would cycle.
        assertThatThrownBy(() -> dependencyService.create(STORY, a.id(),
                new CreateDependencyRequest(STORY, b.id(), DependencyType.BLOCKED_BY)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    @DisplayName("starting a sprint freezes the commitment; later additions are recorded as scope creep")
    void sprintStartFreezesCommitment() {
        ProjectResponse project = newProject("Acme Robotics", "Sprint Flow");

        StoryResponse committed = newStory(project.id(), "Planned work", BigDecimal.valueOf(8));
        SprintResponse sprint = sprintService.create(new CreateSprintRequest(project.id(), "Sprint 1",
                "Ship the thing", java.time.LocalDate.now(), null, (short) 10, BigDecimal.valueOf(20),
                null, null));

        sprintService.addItems(sprint.id(), new AddSprintItemsRequest(List.of(committed.id())));
        SprintResponse started = sprintService.start(sprint.id());

        assertThat(started.status()).isEqualTo(SprintStatus.ACTIVE);
        assertThat(started.committedPoints()).isEqualByComparingTo("8");

        // Work added after the start must not move the commitment.
        StoryResponse lateArrival = newStory(project.id(), "Urgent bug", BigDecimal.valueOf(5));
        sprintService.addItems(started.id(), new AddSprintItemsRequest(List.of(lateArrival.id())));

        SprintResponse afterCreep = sprintService.get(sprint.id());
        assertThat(afterCreep.committedPoints()).isEqualByComparingTo("8");
        assertThat(afterCreep.addedPoints()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("an empty sprint cannot be started")
    void emptySprintCannotStart() {
        ProjectResponse project = newProject("Acme Robotics", "Empty Sprint");
        SprintResponse sprint = sprintService.create(new CreateSprintRequest(project.id(), null, null,
                null, null, null, null, null, null));

        assertThatThrownBy(() -> sprintService.start(sprint.id()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no items");
    }

    @Test
    @DisplayName("only one sprint per project may be active")
    void secondSprintCannotStartWhileOneIsActive() {
        ProjectResponse project = newProject("Acme Robotics", "Single Active");

        StoryResponse one = newStory(project.id(), "One", BigDecimal.ONE);
        StoryResponse two = newStory(project.id(), "Two", BigDecimal.ONE);

        SprintResponse first = sprintService.create(new CreateSprintRequest(project.id(), "S1", null,
                null, null, null, null, null, null));
        sprintService.addItems(first.id(), new AddSprintItemsRequest(List.of(one.id())));
        sprintService.start(first.id());

        SprintResponse second = sprintService.create(new CreateSprintRequest(project.id(), "S2", null,
                null, null, null, null, null, null));
        sprintService.addItems(second.id(), new AddSprintItemsRequest(List.of(two.id())));

        assertThatThrownBy(() -> sprintService.start(second.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already active");
    }

    @Test
    @DisplayName("completing a sprint measures delivery and moves unfinished work to the backlog")
    void sprintCompletionHandlesSpillover() {
        ProjectResponse project = newProject("Acme Robotics", "Spillover Flow");

        StoryResponse done = newStory(project.id(), "Finished", BigDecimal.valueOf(5));
        StoryResponse unfinished = newStory(project.id(), "Not finished", BigDecimal.valueOf(3));

        SprintResponse sprint = sprintService.create(new CreateSprintRequest(project.id(), "Sprint 1",
                null, java.time.LocalDate.now(), null, (short) 10, null, null, null));
        sprintService.addItems(sprint.id(),
                new AddSprintItemsRequest(List.of(done.id(), unfinished.id())));
        sprintService.start(sprint.id());

        storyService.changeStatus(done.id(), new UpdateStoryStatusRequest(StoryStatus.DONE, null));

        CompleteSprintResponse result = sprintService.complete(sprint.id(),
                new CompleteSprintRequest(SpilloverStrategy.BACKLOG, null, "Went well"));

        assertThat(result.sprint().status()).isEqualTo(SprintStatus.COMPLETED);
        assertThat(result.completedStories()).isEqualTo(1);
        assertThat(result.spilledStories()).isEqualTo(1);
        assertThat(result.velocity()).isEqualByComparingTo("5");
        assertThat(result.spilledToSprintId()).isNull();

        // The unfinished story must not still point at a completed sprint.
        assertThat(storyService.get(unfinished.id()).sprintId()).isNull();
        assertThat(storyService.get(done.id()).sprintId()).isEqualTo(sprint.id());
    }

    @Test
    @DisplayName("completing a story records its timestamps and its points count toward velocity")
    void storyCompletionSetsTimestamps() {
        ProjectResponse project = newProject("Acme Robotics", "Status Flow");
        StoryResponse story = newStory(project.id(), "Do the work", BigDecimal.valueOf(3));

        storyService.changeStatus(story.id(), new UpdateStoryStatusRequest(StoryStatus.IN_PROGRESS, null));
        StoryResponse inProgress = storyService.get(story.id());
        assertThat(inProgress.startedAt()).isNotNull();
        assertThat(inProgress.completedAt()).isNull();

        storyService.changeStatus(story.id(), new UpdateStoryStatusRequest(StoryStatus.DONE, null));
        StoryResponse completed = storyService.get(story.id());
        assertThat(completed.completedAt()).isNotNull();
        assertThat(completed.reopenedCount()).isZero();

        // Reopening is counted — the reopen rate is an M3 feature.
        storyService.changeStatus(story.id(), new UpdateStoryStatusRequest(StoryStatus.IN_PROGRESS, null));
        assertThat(storyService.get(story.id()).reopenedCount()).isEqualTo((short) 1);
    }

    @Test
    @DisplayName("blocking a started task moves it out of the in-progress column")
    void blockingATaskChangesItsStatus() {
        ProjectResponse project = newProject("Acme Robotics", "Task Flow");
        StoryResponse story = newStory(project.id(), "Parent story", BigDecimal.valueOf(2));

        TaskResponse task = taskService.create(new CreateTaskRequest(project.id(), story.id(), null, null,
                "Implement it", null, null, null, null, null, 120, null, List.of("java"), null));
        taskService.changeStatus(task.id(), new UpdateTaskStatusRequest(TaskStatus.IN_PROGRESS, null));

        TaskResponse blocked = taskService.block(task.id(), new BlockTaskRequest("Waiting on API keys"));
        assertThat(blocked.blocked()).isTrue();
        assertThat(blocked.status()).isEqualTo(TaskStatus.BLOCKED);

        TaskResponse unblocked = taskService.unblock(task.id());
        assertThat(unblocked.blocked()).isFalse();
        assertThat(unblocked.status()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(unblocked.blockedReason()).isNull();
    }

    @Test
    @DisplayName("subtasks cannot be nested more than one level deep")
    void subtasksCannotNest() {
        ProjectResponse project = newProject("Acme Robotics", "Nesting Guard");

        TaskResponse parent = taskService.create(new CreateTaskRequest(project.id(), null, null, null,
                "Parent", null, null, null, null, null, null, null, null, null));
        TaskResponse child = taskService.create(new CreateTaskRequest(project.id(), null, parent.id(),
                null, "Child", null, null, null, null, null, null, null, null, null));

        assertThat(child.parentTaskId()).isEqualTo(parent.id());
        assertThatThrownBy(() -> taskService.create(new CreateTaskRequest(project.id(), null, child.id(),
                null, "Grandchild", null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("nested");
    }

    @Test
    @DisplayName("the project overview aggregates counts from every delivery module")
    void overviewAggregatesAcrossModules() {
        ProjectResponse project = newProject("Acme Robotics", "Overview Roll Up");

        epicService.create(new CreateEpicRequest(project.id(), "An epic", null, null, null, null, null,
                null, null));
        StoryResponse done = newStory(project.id(), "Done story", BigDecimal.valueOf(5));
        newStory(project.id(), "Open story", BigDecimal.valueOf(3));
        storyService.changeStatus(done.id(), new UpdateStoryStatusRequest(StoryStatus.DONE, null));

        taskService.create(new CreateTaskRequest(project.id(), null, null, null, "A task", null, null,
                null, null, null, null, null, null, null));

        ProjectOverviewResponse overview = projectService.overview(project.id());

        assertThat(overview.epicCount()).isEqualTo(1);
        assertThat(overview.storyCount()).isEqualTo(2);
        assertThat(overview.completedStoryCount()).isEqualTo(1);
        assertThat(overview.taskCount()).isEqualTo(1);
        assertThat(overview.openTaskCount()).isEqualTo(1);
        assertThat(overview.totalStoryPoints()).isEqualByComparingTo("8");
        assertThat(overview.completedStoryPoints()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("delivery-core rows are invisible to another tenant")
    void deliveryRowsAreTenantIsolated() {
        ProjectResponse acmeProject = newProject("Acme Robotics", "Acme Secret");
        newStory(acmeProject.id(), "Acme confidential", BigDecimal.ONE);
        UUID acmeProjectId = acmeProject.id();

        // Switch to a different tenant entirely.
        ProjectResponse globexProject = newProject("Globex Media", "Globex Work");

        assertThat(projectService.search(null, null, null, null,
                new com.sprintiq.pms.common.web.PageQuery()).content())
                .extracting(ProjectResponse::id)
                .contains(globexProject.id())
                .doesNotContain(acmeProjectId);
    }
}
