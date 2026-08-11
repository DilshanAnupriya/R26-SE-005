package com.sprintiq.pms.integration;

import com.sprintiq.pms.modules.analytics.application.TimelineService;
import com.sprintiq.pms.modules.analytics.web.dto.TimelineDtos.*;
import com.sprintiq.pms.modules.backlog.application.EpicService;
import com.sprintiq.pms.modules.backlog.application.StoryService;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.*;
import com.sprintiq.pms.modules.organizations.application.OrganizationProvisioningService;
import com.sprintiq.pms.modules.organizations.domain.Organization;
import com.sprintiq.pms.modules.organizations.domain.WorkspaceRepository;
import com.sprintiq.pms.modules.projects.application.ProjectService;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.*;
import com.sprintiq.pms.modules.sprints.application.SprintService;
import com.sprintiq.pms.modules.sprints.web.dto.SprintDtos.*;
import com.sprintiq.pms.modules.users.domain.User;
import com.sprintiq.pms.modules.users.domain.UserRepository;
import com.sprintiq.pms.modules.users.domain.UserStatus;
import com.sprintiq.pms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The timeline assembled from real rows.
 *
 * <p>What is worth proving here is not that bars appear, but <i>where their dates come
 * from</i>. A Gantt chart that cannot distinguish a date somebody committed to from one
 * the software guessed is a chart that starts arguments, so every bar reports its
 * source and this test pins each rule down.</p>
 */
@Transactional
class TimelineIT extends AbstractIntegrationTest {

    @Autowired OrganizationProvisioningService provisioningService;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired UserRepository userRepository;
    @Autowired ProjectService projectService;
    @Autowired EpicService epicService;
    @Autowired StoryService storyService;
    @Autowired SprintService sprintService;
    @Autowired TimelineService timelineService;

    private UUID projectId;

    @BeforeEach
    void provision() {
        UUID userId = newUser();
        Organization organization = provisioningService.provisionOrganization("Acme Robotics", userId);
        TenantContext.set(TenantContext.Context.builder()
                .organizationId(organization.getId())
                .userId(userId)
                .permissions(Set.of())
                .systemContext(false)
                .build());

        UUID workspaceId = workspaceRepository
                .findByOrganizationIdAndDeletedAtIsNull(organization.getId()).get(0).getId();

        projectId = projectService.create(new CreateProjectRequest(workspaceId, "Payroll SaaS",
                null, "Seeded by TimelineIT", null, null, null, "USD", null, null, "fintech",
                List.of("java"), (short) 14, List.of(), List.of())).id();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private UUID newUser() {
        String email = "owner+%s@example.test".formatted(UUID.randomUUID());
        return userRepository.saveAndFlush(User.builder()
                .email(email)
                .emailNormalized(email.toLowerCase(Locale.ROOT))
                .fullName("Test owner")
                .status(UserStatus.ACTIVE)
                .emailVerifiedAt(java.time.Instant.now())
                .build()).getId();
    }

    private UUID newEpic(String title, LocalDate start, LocalDate target) {
        return epicService.create(new CreateEpicRequest(projectId, title, null, null, null, null,
                null, start, target)).id();
    }

    private StoryResponse newStory(UUID epicId, String title, LocalDate dueDate) {
        return storyService.create(new CreateStoryRequest(projectId, epicId, null, title, null,
                null, null, null, null, BigDecimal.valueOf(5), null, null, null, dueDate, null,
                null, null));
    }

    @Test
    @DisplayName("a story takes the window of the sprint it is committed to, and says so")
    void sprintDatesWin() {
        UUID epicId = newEpic("Authentication", LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 30));
        StoryResponse story = newStory(epicId, "OAuth2 login", null);

        SprintResponse sprint = sprintService.create(new CreateSprintRequest(projectId,
                "Sprint 24", null, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 24),
                null, null, null, null));
        sprintService.addItems(sprint.id(), new AddSprintItemsRequest(List.of(story.id())));

        TimelineResponse timeline = timelineService.timeline(projectId, null, null);
        BarResponse bar = onlyRow(timeline);

        assertThat(bar.dateSource()).isEqualTo(DateSource.SPRINT);
        assertThat(bar.start()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(bar.end()).isEqualTo(LocalDate.of(2026, 8, 24));
    }

    @Test
    @DisplayName("a story with only a due date becomes a marker, not an invented span")
    void dueDateOnly() {
        UUID epicId = newEpic("Authentication", LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 30));
        newStory(epicId, "Session refresh fix", LocalDate.of(2026, 8, 20));

        BarResponse bar = onlyRow(timelineService.timeline(projectId, null, null));

        assertThat(bar.dateSource()).isEqualTo(DateSource.DUE_DATE_ONLY);
        assertThat(bar.start()).isEqualTo(bar.end());
    }

    @Test
    @DisplayName("a story nothing dates is excluded and counted, not given made-up dates")
    void undatedIsCounted() {
        UUID epicId = newEpic("Authentication", LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 30));
        newStory(epicId, "Someday", null);

        TimelineResponse timeline = timelineService.timeline(projectId, null, null);

        assertThat(timeline.undatedCount()).isEqualTo(1);
        assertThat(timeline.groups()).singleElement()
                .satisfies(group -> assertThat(group.rows()).isEmpty());
    }

    @Test
    @DisplayName("an epic with no dates of its own is dated by its stories, marked derived")
    void epicDatesDerived() {
        UUID epicId = newEpic("Authentication", null, null);
        newStory(epicId, "First", LocalDate.of(2026, 8, 12));
        newStory(epicId, "Last", LocalDate.of(2026, 9, 4));

        TimelineResponse timeline = timelineService.timeline(projectId, null, null);
        BarResponse epic = timeline.groups().get(0).bar();

        assertThat(epic.dateSource()).isEqualTo(DateSource.DERIVED);
        assertThat(epic.start()).isEqualTo(LocalDate.of(2026, 8, 12));
        assertThat(epic.end()).isEqualTo(LocalDate.of(2026, 9, 4));
    }

    @Test
    @DisplayName("an epic with neither dates nor datable stories is left off rather than guessed at")
    void undatableEpicIsOmitted() {
        newEpic("Someday", null, null);

        TimelineResponse timeline = timelineService.timeline(projectId, null, null);

        assertThat(timeline.groups()).isEmpty();
        assertThat(timeline.undatedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("the window covers the work and always leaves room after today")
    void windowCoversTheWork() {
        UUID epicId = newEpic("Authentication", LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 30));
        newStory(epicId, "OAuth2 login", LocalDate.of(2026, 9, 4));

        TimelineResponse timeline = timelineService.timeline(projectId, null, null);

        // Starts on a Monday, so the week columns line up with how people say "week of".
        assertThat(timeline.from().getDayOfWeek().getValue()).isEqualTo(1);
        assertThat(timeline.to()).isAfterOrEqualTo(timeline.today().plusWeeks(2));
        assertThat(timeline.weeks()).isNotEmpty();
        assertThat(timeline.weeks().get(0).start()).isEqualTo(timeline.from());
    }

    private static BarResponse onlyRow(TimelineResponse timeline) {
        assertThat(timeline.groups()).hasSize(1);
        assertThat(timeline.groups().get(0).rows()).hasSize(1);
        return timeline.groups().get(0).rows().get(0);
    }
}
