package com.sprintiq.pms.modules.backlog;

import com.sprintiq.pms.common.domain.Priority;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A story's priority changed from the Log-a-change form.
 *
 * <p>Raising it mid-sprint raises requirement-change risk and lowering it lowers it, and
 * the charge scales with how many levels it moved: Low to High costs twice what Low to
 * Medium does, and High to Low hands back twice what High to Medium does.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Story priority changes")
class StoryPriorityChangeTest {

    private static final UUID PROJECT = UUID.randomUUID();

    @Mock private StoryRepository stories;
    @Mock private StoryService storyService;
    @Mock private RequirementChangeService register;

    @ParameterizedTest(name = "{0} → {1}: {2}, {3} level(s)")
    @CsvSource({
            "LOW,    MEDIUM, INCREASE, 1",
            "LOW,    HIGH,   INCREASE, 2",
            "MEDIUM, HIGH,   INCREASE, 1",
            "MEDIUM, LOW,    DECREASE, 1",
            "HIGH,   MEDIUM, DECREASE, 1",
            "HIGH,   LOW,    DECREASE, 2",
    })
    @DisplayName("raising charges, lowering credits, by the levels moved")
    void scaledByLevels(Priority from, Priority to, ChangeImpactDirection direction, int levels) {
        Story story = Story.builder().projectId(PROJECT).itemKey("PAY-6").title("SSO")
                .priority(from).build();
        story.setId(UUID.randomUUID());
        when(stories.findByIdAndDeletedAtIsNull(story.getId())).thenReturn(Optional.of(story));
        when(register.log(eq(PROJECT), any(), anyInt())).thenReturn(new RequirementChangeResponse(
                UUID.randomUUID(), PROJECT, null, null, null, story.getId(),
                RequirementChangeType.PRIORITY_CHANGED, direction, null, null, null,
                BigDecimal.ONE, Instant.now()));

        new StoryRequirementChangeService(stories, storyService, register).apply(story.getId(),
                new ApplyStoryChangeRequest(RequirementChangeType.PRIORITY_CHANGED, to,
                        null, null, null, null, null, null, true));

        ArgumentCaptor<LogRequirementChangeRequest> logged =
                ArgumentCaptor.forClass(LogRequirementChangeRequest.class);
        verify(register).log(eq(PROJECT), logged.capture(), eq(levels));
        assertThat(logged.getValue().impactDirection()).isEqualTo(direction);
        assertThat(story.getPriority()).isEqualTo(to);
    }
}
