package com.sprintiq.pms.integration;

import com.sprintiq.pms.modules.analytics.application.CalendarService;
import com.sprintiq.pms.modules.analytics.web.dto.CalendarDtos.*;
import com.sprintiq.pms.modules.backlog.application.EpicService;
import com.sprintiq.pms.modules.backlog.application.StoryService;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.*;
import com.sprintiq.pms.modules.organizations.application.OrganizationProvisioningService;
import com.sprintiq.pms.modules.organizations.domain.Organization;
import com.sprintiq.pms.modules.organizations.domain.WorkspaceRepository;
import com.sprintiq.pms.modules.projects.application.MilestoneService;
import com.sprintiq.pms.modules.projects.application.ProjectService;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.*;
import com.sprintiq.pms.modules.roadmap.application.RoadmapService;
import com.sprintiq.pms.modules.roadmap.web.dto.RoadmapDtos.CreateInitiativeRequest;
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
 * The calendar, and the roadmap date window it leans on.
 *
 * <p>The window is here because it shipped untested and broke: the roadmap screen never
 * passed a from/to, so the first caller that did — this calendar — got a 500 from
 * Postgres refusing a bare untyped parameter. A query parameter no caller exercises is
 * a query parameter nobody has run.</p>
 */
@Transactional
class CalendarIT extends AbstractIntegrationTest {

    @Autowired OrganizationProvisioningService provisioningService;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired UserRepository userRepository;
    @Autowired ProjectService projectService;
    @Autowired EpicService epicService;
    @Autowired StoryService storyService;
    @Autowired MilestoneService milestoneService;
    @Autowired RoadmapService roadmapService;
    @Autowired CalendarService calendarService;

    private UUID projectId;
    private static final LocalDate ANCHOR = LocalDate.of(2026, 8, 12);

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
                null, "Seeded by CalendarIT", null, null, null, "USD", null, null, "fintech",
                List.of("java"), (short) 14, List.of(), List.of(), null, null, null, null)).id();
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

    private void newStory(String title, LocalDate dueDate) {
        UUID epicId = epicService.create(new CreateEpicRequest(projectId, "Authentication", null,
                null, null, null, null, null, null)).id();
        storyService.create(new CreateStoryRequest(projectId, epicId, null, title, null, null,
                null, null, null, BigDecimal.valueOf(5), null, null, null, dueDate, null, null,
                null));
    }

    private void newInitiative(String title, LocalDate targetDate) {
        roadmapService.create(new CreateInitiativeRequest(title, null, projectId,
                targetDate.minusMonths(2), targetDate, null, null, null, List.of()));
    }

    // ------------------------------------------------------- the window that broke

    @Test
    @DisplayName("the roadmap accepts a date window without Postgres refusing the parameters")
    void roadmapWindowIsQueryable() {
        newInitiative("In the window", LocalDate.of(2026, 8, 20));
        newInitiative("After the window", LocalDate.of(2026, 12, 20));

        var inWindow = roadmapService.roadmap(null, null,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        assertThat(inWindow.totalInitiatives()).isEqualTo(1);
        assertThat(inWindow.lanes().get(0).initiatives().get(0).title()).isEqualTo("In the window");
    }

    @Test
    @DisplayName("an undated initiative survives any window, because it was never scheduled out")
    void undatedSurvivesTheWindow() {
        roadmapService.create(new CreateInitiativeRequest("Someday", null, projectId,
                null, null, null, null, null, List.of()));

        var narrow = roadmapService.roadmap(null, null,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2));

        assertThat(narrow.totalInitiatives()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the grid

    @Test
    @DisplayName("the grid is whole weeks, Monday first, with the padding marked")
    void gridIsWholeWeeks() {
        CalendarResponse calendar = calendarService.month(ANCHOR, null);

        assertThat(calendar.days()).isNotEmpty();
        assertThat(calendar.days().size() % 7).isZero();
        assertThat(calendar.from().getDayOfWeek().getValue()).isEqualTo(1);
        assertThat(calendar.days().get(0).date()).isEqualTo(calendar.from());

        // August 2026 begins on a Saturday, so the grid opens with padding from July.
        assertThat(calendar.days().get(0).inMonth()).isFalse();
        assertThat(calendar.days().stream().filter(DayResponse::inMonth)).hasSize(31);
    }

    @Test
    @DisplayName("a story's due date lands on its own day, not the whole week")
    void storyDueDateLandsOnItsDay() {
        newStory("FX rate provider integration", LocalDate.of(2026, 8, 20));

        CalendarResponse calendar = calendarService.month(ANCHOR, null);
        DayResponse day = dayOf(calendar, LocalDate.of(2026, 8, 20));

        assertThat(day.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.kind()).isEqualTo(EntryKind.STORY_DUE);
            assertThat(entry.title()).isEqualTo("FX rate provider integration");
            assertThat(entry.projectKey()).isEqualTo("PS");
        });
    }

    @Test
    @DisplayName("a milestone past its date and still open is flagged overdue")
    void overdueMilestone() {
        milestoneService.create(projectId,
                new CreateMilestoneRequest("Payroll v2 GA", null, LocalDate.of(2026, 8, 5), null));

        CalendarResponse calendar = calendarService.month(ANCHOR, null);
        DayResponse day = dayOf(calendar, LocalDate.of(2026, 8, 5));

        assertThat(day.entries()).singleElement()
                .satisfies(entry -> assertThat(entry.kind()).isEqualTo(EntryKind.MILESTONE));
    }

    @Test
    @DisplayName("dates outside the grid are left off rather than clamped onto its edges")
    void outsideTheGridIsOmitted() {
        newStory("Next year", LocalDate.of(2027, 3, 1));

        CalendarResponse calendar = calendarService.month(ANCHOR, null);

        // Clamping would pile every distant deadline onto the last cell, which reads as
        // a catastrophic week rather than as work that is simply elsewhere.
        assertThat(calendar.totalEntries()).isZero();
    }

    @Test
    @DisplayName("weekends and today are marked, so the client never recomputes the calendar")
    void weekendsAndTodayAreMarked() {
        CalendarResponse calendar = calendarService.month(ANCHOR, null);

        assertThat(calendar.days().stream().filter(DayResponse::weekend)).isNotEmpty();
        assertThat(calendar.days().stream().filter(DayResponse::weekend))
                .allSatisfy(day -> assertThat(day.date().getDayOfWeek().getValue()).isGreaterThan(5));
    }

    private static DayResponse dayOf(CalendarResponse calendar, LocalDate date) {
        return calendar.days().stream()
                .filter(day -> day.date().equals(date))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No cell for " + date));
    }
}
