package com.sprintiq.pms.integration;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.AccessDeniedAppException;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.modules.analytics.application.AnalyticsService;
import com.sprintiq.pms.modules.analytics.web.dto.ReportDtos.*;
import com.sprintiq.pms.modules.backlog.application.StoryService;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.CreateStoryRequest;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.StoryResponse;
import com.sprintiq.pms.modules.comments.application.CommentService;
import com.sprintiq.pms.modules.comments.application.MentionParser;
import com.sprintiq.pms.modules.comments.web.dto.CommentDtos.*;
import com.sprintiq.pms.modules.notifications.application.NotificationService;
import com.sprintiq.pms.modules.notifications.domain.NotificationType;
import com.sprintiq.pms.modules.notifications.web.dto.NotificationDtos.*;
import com.sprintiq.pms.modules.organizations.application.OrganizationProvisioningService;
import com.sprintiq.pms.modules.organizations.domain.Organization;
import com.sprintiq.pms.modules.organizations.domain.WorkspaceRepository;
import com.sprintiq.pms.modules.projects.application.ProjectService;
import com.sprintiq.pms.modules.projects.domain.ProjectRole;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.AddProjectMemberRequest;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.CreateProjectRequest;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.ProjectResponse;
import com.sprintiq.pms.modules.settings.application.WorkspaceSettingsService;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Phase 4 end to end: comments and their notification fan-out, the inbox, reports and
 * workspace settings, against real Postgres.
 *
 * <p>The assertions are about the rules that make these features tolerable rather than
 * merely present — nobody is notified about their own comment, a mention reaches exactly
 * the person named, editing a comment does not re-notify everyone, and a percentile
 * report reports percentiles rather than a mean dressed up as one.</p>
 */
@Transactional
class CollaborationIT extends AbstractIntegrationTest {

    @Autowired OrganizationProvisioningService provisioningService;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired UserRepository userRepository;
    @Autowired ProjectService projectService;
    @Autowired StoryService storyService;
    @Autowired CommentService commentService;
    @Autowired NotificationService notificationService;
    @Autowired AnalyticsService analyticsService;
    @Autowired WorkspaceSettingsService settingsService;
    @Autowired com.sprintiq.pms.modules.organizations.domain.OrgMemberRepository orgMembers;
    @Autowired com.sprintiq.pms.modules.organizations.domain.RoleRepository roles;

    private UUID ownerId;
    private UUID workspaceId;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------ fixtures

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

    /**
     * A colleague who is actually a member of the current organization.
     *
     * <p>A {@code users} row alone is not a member — assignment, mentions and project
     * membership all check {@code org_members}, which is exactly the check that stops
     * one tenant referencing another's people.</p>
     */
    private UUID newMember(String label) {
        UUID userId = newUser(label);
        UUID roleId = roles.findByCodeAndSystemRoleTrue("MEMBER").orElseThrow().getId();
        var member = com.sprintiq.pms.modules.organizations.domain.OrgMember.builder()
                .userId(userId)
                .roleId(roleId)
                .status(com.sprintiq.pms.modules.organizations.domain.MembershipStatus.ACTIVE)
                .joinedAt(java.time.Instant.now())
                .build();
        member.setOrganizationId(TenantContext.requireOrganizationId());
        orgMembers.saveAndFlush(member);
        return userId;
    }

    private ProjectResponse newProject(String name) {
        ownerId = newUser("owner");
        Organization organization = provisioningService.provisionOrganization("Acme Robotics", ownerId);
        actAs(organization.getId(), ownerId);

        workspaceId = workspaceRepository
                .findByOrganizationIdAndDeletedAtIsNull(organization.getId()).get(0).getId();

        return projectService.create(new CreateProjectRequest(workspaceId, name, null,
                "Created by CollaborationIT", null, null, null, "USD", null, null, "fintech",
                List.of("java"), (short) 14, List.of(), List.of(), null, null, null, null));
    }

    private void actAs(UUID organizationId, UUID userId) {
        TenantContext.set(TenantContext.Context.builder()
                .organizationId(organizationId)
                .userId(userId)
                .permissions(Set.of("comment:moderate"))
                .systemContext(false)
                .build());
    }

    private StoryResponse newStory(UUID projectId, String title, BigDecimal points) {
        return storyService.create(new CreateStoryRequest(projectId, null, null, title,
                "A description.", "One\nTwo", null, null, null, points, null, null, null, null,
                null, null, null));
    }

    // ------------------------------------------------------------------- mentions

    @Test
    @DisplayName("mentions are parsed from the id, not the display name")
    void mentionParsingUsesIds() {
        UUID target = UUID.randomUUID();
        String body = "Can you look at this @[Priya Raman](%s)? Also @[Nobody](not-a-uuid)"
                .formatted(target);

        assertThat(MentionParser.parse(body)).containsExactly(target);
        assertThat(MentionParser.toPlainText(body)).contains("@Priya Raman");
        assertThat(MentionParser.toPlainText(body)).doesNotContain(target.toString());
    }

    @Test
    @DisplayName("a mention notifies the person named and nobody else")
    void mentionNotifiesTheNamedPerson() {
        ProjectResponse project = newProject("Mobile Banking");
        UUID colleague = newMember("colleague");
        projectService.addMember(project.id(),
                new AddProjectMemberRequest(colleague, ProjectRole.DEVELOPER, false, null));
        StoryResponse story = newStory(project.id(), "Add 2FA", BigDecimal.valueOf(5));

        commentService.create(new CreateCommentRequest(EntityType.STORY, story.id(), null,
                "Thoughts, @[Test colleague](%s)?".formatted(colleague)));

        actAs(TenantContext.requireOrganizationId(), colleague);
        List<NotificationResponse> inbox =
                notificationService.inbox(true, null, new PageQuery()).content();

        assertThat(inbox).hasSize(1);
        assertThat(inbox.get(0).type()).isEqualTo(NotificationType.MENTION);
        assertThat(inbox.get(0).read()).isFalse();
        assertThat(inbox.get(0).entityId()).isEqualTo(story.id());
    }

    @Test
    @DisplayName("you are never notified about your own comment")
    void authorsAreNotNotifiedAboutThemselves() {
        ProjectResponse project = newProject("Mobile Banking");
        StoryResponse story = newStory(project.id(), "Self comment", null);

        commentService.create(new CreateCommentRequest(EntityType.STORY, story.id(), null,
                "Note to self @[Test owner](%s)".formatted(ownerId)));

        assertThat(notificationService.inbox(false, null, new PageQuery()).content()).isEmpty();
        assertThat(notificationService.unreadCount().unread()).isZero();
    }

    @Test
    @DisplayName("editing a comment only notifies people newly mentioned")
    void editingOnlyNotifiesNewMentions() {
        ProjectResponse project = newProject("Mobile Banking");
        UUID first = newMember("first");
        UUID second = newMember("second");
        projectService.addMember(project.id(), new AddProjectMemberRequest(first, ProjectRole.DEVELOPER, false, null));
        projectService.addMember(project.id(), new AddProjectMemberRequest(second, ProjectRole.DEVELOPER, false, null));
        StoryResponse story = newStory(project.id(), "Edited thread", null);

        UUID organizationId = TenantContext.requireOrganizationId();
        CommentResponse comment = commentService.create(new CreateCommentRequest(
                EntityType.STORY, story.id(), null,
                "Hello @[Test first](%s)".formatted(first)));

        commentService.update(comment.id(), new UpdateCommentRequest(
                "Hello @[Test first](%s) and @[Test second](%s)".formatted(first, second)));

        actAs(organizationId, first);
        assertThat(notificationService.inbox(false, null, new PageQuery()).content())
                .as("the original mention is not repeated on edit").hasSize(1);

        actAs(organizationId, second);
        assertThat(notificationService.inbox(false, null, new PageQuery()).content())
                .as("the newly added mention is delivered").hasSize(1);
    }

    // ------------------------------------------------------------------- threads

    @Test
    @DisplayName("replying to a reply joins the same thread rather than nesting deeper")
    void repliesAreFlattenedToOneLevel() {
        ProjectResponse project = newProject("Mobile Banking");
        StoryResponse story = newStory(project.id(), "Threaded", null);

        CommentResponse root = commentService.create(new CreateCommentRequest(
                EntityType.STORY, story.id(), null, "Root"));
        CommentResponse reply = commentService.create(new CreateCommentRequest(
                EntityType.STORY, story.id(), root.id(), "Reply"));
        CommentResponse nested = commentService.create(new CreateCommentRequest(
                EntityType.STORY, story.id(), reply.id(), "Reply to the reply"));

        assertThat(reply.parentCommentId()).isEqualTo(root.id());
        assertThat(nested.parentCommentId())
                .as("attached to the root, not to the reply").isEqualTo(root.id());

        var thread = commentService.list(EntityType.STORY, story.id(), new PageQuery());
        assertThat(thread.content()).hasSize(1);
        assertThat(thread.content().get(0).replies()).hasSize(2);
    }

    @Test
    @DisplayName("deleting a comment takes its replies with it")
    void deletingARootRemovesItsReplies() {
        ProjectResponse project = newProject("Mobile Banking");
        StoryResponse story = newStory(project.id(), "Doomed thread", null);

        CommentResponse root = commentService.create(new CreateCommentRequest(
                EntityType.STORY, story.id(), null, "Root"));
        commentService.create(new CreateCommentRequest(
                EntityType.STORY, story.id(), root.id(), "Reply"));

        commentService.delete(root.id());

        assertThat(commentService.count(EntityType.STORY, story.id()).count()).isZero();
    }

    @Test
    @DisplayName("moderation can delete another person's comment but not rewrite it")
    void moderatorsDeleteButDoNotEdit() {
        ProjectResponse project = newProject("Mobile Banking");
        UUID author = newMember("author");
        projectService.addMember(project.id(), new AddProjectMemberRequest(author, ProjectRole.DEVELOPER, false, null));
        StoryResponse story = newStory(project.id(), "Moderated", null);

        UUID organizationId = TenantContext.requireOrganizationId();
        actAs(organizationId, author);
        CommentResponse comment = commentService.create(new CreateCommentRequest(
                EntityType.STORY, story.id(), null, "Something regrettable"));

        actAs(organizationId, ownerId);
        assertThatThrownBy(() -> commentService.update(comment.id(),
                new UpdateCommentRequest("Something I would rather they had said")))
                .isInstanceOf(AccessDeniedAppException.class);

        commentService.delete(comment.id());
        assertThat(commentService.count(EntityType.STORY, story.id()).count()).isZero();
    }

    // --------------------------------------------------------------------- inbox

    @Test
    @DisplayName("marking everything read clears the badge in one statement")
    void markAllReadClearsTheBadge() {
        ProjectResponse project = newProject("Mobile Banking");
        UUID colleague = newMember("colleague");
        projectService.addMember(project.id(),
                new AddProjectMemberRequest(colleague, ProjectRole.DEVELOPER, false, null));
        StoryResponse story = newStory(project.id(), "Noisy", null);
        UUID organizationId = TenantContext.requireOrganizationId();

        for (int index = 0; index < 3; index++) {
            commentService.create(new CreateCommentRequest(EntityType.STORY, story.id(), null,
                    "Ping %d @[Test colleague](%s)".formatted(index, colleague)));
        }

        actAs(organizationId, colleague);
        assertThat(notificationService.unreadCount().unread()).isEqualTo(3);

        notificationService.markAllRead();
        assertThat(notificationService.unreadCount().unread()).isZero();
    }

    @Test
    @DisplayName("turning a notification type off stops it being delivered")
    void preferencesSuppressDelivery() {
        ProjectResponse project = newProject("Mobile Banking");
        UUID colleague = newMember("colleague");
        projectService.addMember(project.id(),
                new AddProjectMemberRequest(colleague, ProjectRole.DEVELOPER, false, null));
        StoryResponse story = newStory(project.id(), "Muted", null);
        UUID organizationId = TenantContext.requireOrganizationId();

        actAs(organizationId, colleague);
        notificationService.updatePreferences(new UpdateNotificationPreferencesRequest(
                List.of(new NotificationPreferenceEntry(NotificationType.MENTION, false, false, false))));

        actAs(organizationId, ownerId);
        commentService.create(new CreateCommentRequest(EntityType.STORY, story.id(), null,
                "You will not see this @[Test colleague](%s)".formatted(colleague)));

        actAs(organizationId, colleague);
        assertThat(notificationService.inbox(false, null, new PageQuery()).content()).isEmpty();
    }

    @Test
    @DisplayName("preferences list every type, including ones never touched")
    void preferencesAreCompleteWithoutBeingProvisioned() {
        newProject("Mobile Banking");

        List<NotificationPreferenceResponse> preferences = notificationService.preferencesOf();

        assertThat(preferences).hasSize(NotificationType.values().length);
        assertThat(preferences).allMatch(NotificationPreferenceResponse::inApp);
    }

    // ------------------------------------------------------------------- reports

    @Test
    @DisplayName("cycle time reports percentiles, not just an average")
    void cycleTimeReportsPercentiles() {
        ProjectResponse project = newProject("Mobile Banking");
        for (int index = 0; index < 5; index++) {
            StoryResponse story = newStory(project.id(), "Item " + index, BigDecimal.valueOf(3));
            storyService.changeStatus(story.id(),
                    new com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.UpdateStoryStatusRequest(
                            com.sprintiq.pms.modules.backlog.domain.StoryStatus.IN_PROGRESS, null));
            storyService.changeStatus(story.id(),
                    new com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.UpdateStoryStatusRequest(
                            com.sprintiq.pms.modules.backlog.domain.StoryStatus.DONE, null));
        }

        DurationDistributionResponse cycleTime = analyticsService.cycleTime(project.id());

        assertThat(cycleTime.sampleSize()).isEqualTo(5);
        assertThat(cycleTime.unit()).isEqualTo("days");
        assertThat(cycleTime.p95()).isGreaterThanOrEqualTo(cycleTime.median());
        assertThat(cycleTime.longest()).isGreaterThanOrEqualTo(cycleTime.p95());
    }

    @Test
    @DisplayName("the productivity report ships without a ranking, and says why")
    void productivityIsNotALeagueTable() {
        ProjectResponse project = newProject("Mobile Banking");
        newStory(project.id(), "Something", BigDecimal.valueOf(3));

        ProductivityReportResponse report = analyticsService.productivity(project.id(), null);

        assertThat(report.note()).contains("not an output measure");
    }

    @Test
    @DisplayName("completion rate counts both items and points")
    void completionRateCountsItemsAndPoints() {
        ProjectResponse project = newProject("Mobile Banking");
        StoryResponse done = newStory(project.id(), "Done", BigDecimal.valueOf(5));
        newStory(project.id(), "Not done", BigDecimal.valueOf(5));

        storyService.changeStatus(done.id(),
                new com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.UpdateStoryStatusRequest(
                        com.sprintiq.pms.modules.backlog.domain.StoryStatus.DONE, null));

        CompletionRateResponse rate = analyticsService.completionRate(project.id());

        assertThat(rate.totalItems()).isEqualTo(2);
        assertThat(rate.completedItems()).isEqualTo(1);
        assertThat(rate.completionPct()).isEqualByComparingTo(BigDecimal.valueOf(50.0));
        assertThat(rate.pointsCompletionPct()).isEqualByComparingTo(BigDecimal.valueOf(50.0));
    }

    // ------------------------------------------------------------------ settings

    @Test
    @DisplayName("workspace settings return defaults until they are overridden")
    void settingsFallBackToDefaults() {
        newProject("Mobile Banking");

        Map<String, Object> defaults = settingsService.settingsOf(workspaceId);
        assertThat(defaults).containsKey("sprint.default_length_days");
        assertThat(defaults.get("estimation.scale")).isEqualTo("FIBONACCI");

        Map<String, Object> updated = settingsService.update(workspaceId,
                Map.of("estimation.scale", "TSHIRT"));

        assertThat(updated.get("estimation.scale")).isEqualTo("TSHIRT");
        assertThat(updated).as("untouched keys keep their defaults")
                .containsKey("sprint.default_length_days");
    }
}
