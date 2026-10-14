package com.sprintiq.pms.modules.backlog;

import com.sprintiq.pms.common.error.BusinessRuleException;
import com.sprintiq.pms.modules.backlog.application.StoryRequirementChangeService;
import com.sprintiq.pms.modules.backlog.application.StoryService;
import com.sprintiq.pms.modules.backlog.domain.Story;
import com.sprintiq.pms.modules.backlog.domain.StoryRepository;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.ApplyStoryChangeRequest;
import com.sprintiq.pms.modules.projects.application.RequirementChangeService;
import com.sprintiq.pms.modules.projects.domain.ChangeImpactDirection;
import com.sprintiq.pms.modules.projects.domain.RequirementChangeType;
import com.sprintiq.pms.modules.projects.web.dto.RequirementChangeDtos.LogRequirementChangeRequest;
import com.sprintiq.pms.modules.projects.web.dto.RequirementChangeDtos.RequirementChangeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A scope change logged against a story carries the updated scope text with it.
 *
 * <p>The Log-a-change form shows the story's description when scope is added or removed,
 * so the change says what the scope now is as well as how big it now is. The size still
 * decides the charge — the text is recorded, not measured.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Story scope changes")
class StoryScopeChangeTest {

    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID SPRINT = UUID.randomUUID();

    @Mock private StoryRepository stories;
    @Mock private StoryService storyService;
    @Mock private RequirementChangeService register;

    private StoryRequirementChangeService service;
    private Story story;

    @BeforeEach
    void setUp() {
        service = new StoryRequirementChangeService(stories, storyService, register);
        story = Story.builder()
                .projectId(PROJECT)
                .sprintId(SPRINT)
                .itemKey("PAY-6")
                .title("Enforce SSO per organization")
                .description("Admins can require SSO.")
                .storyPoints(BigDecimal.valueOf(5))
                .build();
        story.setId(UUID.randomUUID());
        when(stories.findByIdAndDeletedAtIsNull(story.getId())).thenReturn(Optional.of(story));
    }

    private void registerAnswers(RequirementChangeType type, ChangeImpactDirection direction) {
        when(register.log(eq(PROJECT), any(), anyInt())).thenReturn(new RequirementChangeResponse(
                UUID.randomUUID(), PROJECT, SPRINT, null, null, story.getId(), type, direction,
                null, null, null, BigDecimal.valueOf(8), Instant.now()));
    }

    private ApplyStoryChangeRequest scope(RequirementChangeType type, String text, double points) {
        return new ApplyStoryChangeRequest(type, null, text, null, BigDecimal.valueOf(points),
                null, null, null, true);
    }

    @Test
    @DisplayName("scope added updates the points and the scope text together")
    void addedWritesBoth() {
        registerAnswers(RequirementChangeType.SCOPE_ADDED, ChangeImpactDirection.INCREASE);

        service.apply(story.getId(), scope(RequirementChangeType.SCOPE_ADDED,
                "Admins can require SSO, and API tokens must use it too.", 8));

        assertThat(story.getStoryPoints()).isEqualByComparingTo("8");
        assertThat(story.getDescription()).isEqualTo("Admins can require SSO, and API tokens must use it too.");

        ArgumentCaptor<LogRequirementChangeRequest> logged =
                ArgumentCaptor.forClass(LogRequirementChangeRequest.class);
        verify(register).log(eq(PROJECT), logged.capture(), eq(1));
        assertThat(logged.getValue().changeType()).isEqualTo(RequirementChangeType.SCOPE_ADDED);
        assertThat(logged.getValue().sprintId()).isEqualTo(SPRINT);
    }

    @Test
    @DisplayName("scope removed can cut the scope text as well as the size")
    void removedWritesBoth() {
        registerAnswers(RequirementChangeType.SCOPE_REMOVED, ChangeImpactDirection.DECREASE);

        service.apply(story.getId(), scope(RequirementChangeType.SCOPE_REMOVED, "SSO only.", 3));

        assertThat(story.getStoryPoints()).isEqualByComparingTo("3");
        assertThat(story.getDescription()).isEqualTo("SSO only.");
    }

    @Test
    @DisplayName("without updated scope text, the story's description is left alone")
    void noTextKeepsDescription() {
        registerAnswers(RequirementChangeType.SCOPE_ADDED, ChangeImpactDirection.INCREASE);

        service.apply(story.getId(), scope(RequirementChangeType.SCOPE_ADDED, null, 8));

        assertThat(story.getDescription()).isEqualTo("Admins can require SSO.");
    }

    @Test
    @DisplayName("the size still has to move the way the type claims, whatever the text says")
    void sizeStillDecides() {
        assertThatThrownBy(() -> service.apply(story.getId(),
                scope(RequirementChangeType.SCOPE_ADDED, "Much more scope written here.", 5)))
                .isInstanceOf(BusinessRuleException.class);

        assertThat(story.getDescription()).isEqualTo("Admins can require SSO.");
    }
}
