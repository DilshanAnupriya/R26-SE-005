package com.sprintiq.pms.modules.backlog;

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
 * A story's acceptance criteria edited from the Log-a-change form, one criterion per line.
 *
 * <p>Each criterion added raises requirement-change risk and each one deleted lowers it,
 * so the charge is the per-criterion weight times how many were added or removed.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Story acceptance-criteria changes")
class StoryCriteriaChangeTest {

    private static final UUID PROJECT = UUID.randomUUID();
    private static final String TWO = "Admins can require SSO\nMembers without SSO are signed out";

    @Mock private StoryRepository stories;
    @Mock private StoryService storyService;
    @Mock private RequirementChangeService register;

    @ParameterizedTest(name = "{0}: {1}, {2} criterion/criteria")
    @CsvSource(delimiter = '|', emptyValue = "", value = {
            "Admins can require SSO;Members without SSO are signed out;API tokens need SSO | INCREASE | 1",
            "Admins can require SSO;Members without SSO are signed out;API tokens need SSO;Audit log entry | INCREASE | 2",
            "Admins can require SSO | DECREASE | 1",
            "'' | DECREASE | 2",
    })
    @DisplayName("added criteria charge, deleted ones credit, by how many")
    void scaledByCount(String after, ChangeImpactDirection direction, int count) {
        Story story = Story.builder().projectId(PROJECT).itemKey("PAY-6").title("SSO")
                .acceptanceCriteria(TWO).build();
        story.setId(UUID.randomUUID());
        when(stories.findByIdAndDeletedAtIsNull(story.getId())).thenReturn(Optional.of(story));
        when(register.log(eq(PROJECT), any(), anyInt())).thenReturn(new RequirementChangeResponse(
                UUID.randomUUID(), PROJECT, null, null, null, story.getId(),
                RequirementChangeType.ACCEPTANCE_CRITERIA_CHANGED, direction, null, null, null,
                BigDecimal.ONE, Instant.now()));
        // One criterion per line; the data separates them with ";" to keep each case on one row.
        String criteria = after.replace(";", "\n");

        new StoryRequirementChangeService(stories, storyService, register).apply(story.getId(),
                new ApplyStoryChangeRequest(RequirementChangeType.ACCEPTANCE_CRITERIA_CHANGED,
                        null, null, criteria, null, null, null, null, true));

        ArgumentCaptor<LogRequirementChangeRequest> logged =
                ArgumentCaptor.forClass(LogRequirementChangeRequest.class);
        verify(register).log(eq(PROJECT), logged.capture(), eq(count));
        assertThat(logged.getValue().impactDirection()).isEqualTo(direction);
        assertThat(story.getAcceptanceCriteria()).isEqualTo(criteria);
    }
}
