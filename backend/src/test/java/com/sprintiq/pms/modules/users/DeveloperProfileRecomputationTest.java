package com.sprintiq.pms.modules.users;

import com.sprintiq.pms.modules.backlog.domain.Story;
import com.sprintiq.pms.modules.backlog.domain.StoryRepository;
import com.sprintiq.pms.modules.backlog.domain.StoryStatus;
import com.sprintiq.pms.modules.backlog.domain.StoryType;
import com.sprintiq.pms.modules.sprints.domain.Sprint;
import com.sprintiq.pms.modules.sprints.domain.SprintRepository;
import com.sprintiq.pms.modules.sprints.domain.SprintStatus;
import com.sprintiq.pms.modules.users.application.DeveloperProfileRecomputationService;
import com.sprintiq.pms.modules.users.domain.DeveloperProfile;
import com.sprintiq.pms.modules.users.domain.DeveloperProfileRepository;
import com.sprintiq.pms.modules.users.domain.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("Developer Profile Behavioral Metrics Recomputation")
class DeveloperProfileRecomputationTest {

    private DeveloperProfileRepository profileRepository;
    private StoryRepository storyRepository;
    private SprintRepository sprintRepository;
    private UserRepository userRepository;
    private DeveloperProfileRecomputationService service;

    @BeforeEach
    void setUp() {
        profileRepository = mock(DeveloperProfileRepository.class);
        storyRepository = mock(StoryRepository.class);
        sprintRepository = mock(SprintRepository.class);
        userRepository = mock(UserRepository.class);

        when(profileRepository.save(any(DeveloperProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        service = new DeveloperProfileRecomputationService(
                profileRepository, storyRepository, sprintRepository, userRepository);
    }

    @Nested
    @DisplayName("Empty or Cold-Start History")
    class ColdStart {

        @Test
        @DisplayName("User with zero assigned stories retains default baseline scores")
        void noAssignedStories() {
            UUID userId = UUID.randomUUID();
            when(profileRepository.findByUserId(userId)).thenReturn(Optional.empty());
            when(storyRepository.findByAssigneeIdAndDeletedAtIsNull(userId)).thenReturn(Collections.emptyList());

            DeveloperProfile profile = service.recomputeForUser(userId);

            assertThat(profile.getTasksCompleted()).isZero();
            assertThat(profile.getAvgVelocity()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(profile.getDefectRate()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(profile.getReopenRate()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(profile.getConsistencyScore()).isEqualByComparingTo(BigDecimal.valueOf(0.500));
            assertThat(profile.getLearningRate()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(profile.getLastComputedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Active Multi-Sprint Delivery History")
    class MultiSprintCalculations {

        @Test
        @DisplayName("Correctly calculates avg velocity, defect rate, reopen rate, consistency, and learning rate")
        void multiSprintHistory() {
            UUID userId = UUID.randomUUID();
            UUID sprint1Id = UUID.randomUUID();
            UUID sprint2Id = UUID.randomUUID();
            UUID sprint3Id = UUID.randomUUID();

            Sprint s1 = Sprint.builder().sequenceNo(1).status(SprintStatus.COMPLETED).build();
            s1.setId(sprint1Id);
            Sprint s2 = Sprint.builder().sequenceNo(2).status(SprintStatus.COMPLETED).build();
            s2.setId(sprint2Id);
            Sprint s3 = Sprint.builder().sequenceNo(3).status(SprintStatus.COMPLETED).build();
            s3.setId(sprint3Id);

            when(sprintRepository.findAllById(any())).thenReturn(List.of(s1, s2, s3));

            // Sprint 1: 2 stories (5 pts, 3 pts) = 8 pts total. 1 bug, 0 reopen.
            Story story1 = Story.builder().assigneeId(userId).sprintId(sprint1Id)
                    .storyPoints(BigDecimal.valueOf(5)).status(StoryStatus.DONE).type(StoryType.STORY).reopenedCount((short) 0).build();
            story1.setId(UUID.randomUUID());
            Story story2 = Story.builder().assigneeId(userId).sprintId(sprint1Id)
                    .storyPoints(BigDecimal.valueOf(3)).status(StoryStatus.DONE).type(StoryType.BUG).reopenedCount((short) 0).build();
            story2.setId(UUID.randomUUID());

            // Sprint 2: 1 story (8 pts) = 8 pts total. 1 reopen.
            Story story3 = Story.builder().assigneeId(userId).sprintId(sprint2Id)
                    .storyPoints(BigDecimal.valueOf(8)).status(StoryStatus.DONE).type(StoryType.STORY).reopenedCount((short) 1).build();
            story3.setId(UUID.randomUUID());

            // Sprint 3: 2 stories (5 pts, 5 pts) = 10 pts total. 0 bugs, 0 reopen.
            Story story4 = Story.builder().assigneeId(userId).sprintId(sprint3Id)
                    .storyPoints(BigDecimal.valueOf(5)).status(StoryStatus.DONE).type(StoryType.STORY).reopenedCount((short) 0).build();
            story4.setId(UUID.randomUUID());
            Story story5 = Story.builder().assigneeId(userId).sprintId(sprint3Id)
                    .storyPoints(BigDecimal.valueOf(5)).status(StoryStatus.DONE).type(StoryType.STORY).reopenedCount((short) 0).build();
            story5.setId(UUID.randomUUID());

            when(storyRepository.findByAssigneeIdAndDeletedAtIsNull(userId))
                    .thenReturn(List.of(story1, story2, story3, story4, story5));
            when(profileRepository.findByUserId(userId)).thenReturn(Optional.empty());

            DeveloperProfile profile = service.recomputeForUser(userId);

            assertThat(profile.getTasksCompleted()).isEqualTo(5);
            // S1 (8pts excluding bug=5pts velocity), S2 (8pts), S3 (10pts) -> Total vel = 23 pts / 3 sprints = ~7.67
            assertThat(profile.getAvgVelocity()).isGreaterThan(BigDecimal.valueOf(7.0));

            // Defect rate: 1 BUG out of 5 completed items = 0.2000
            assertThat(profile.getDefectRate()).isEqualByComparingTo(BigDecimal.valueOf(0.2000));

            // Reopen rate: 1 reopen out of 5 completed items = 0.2000
            assertThat(profile.getReopenRate()).isEqualByComparingTo(BigDecimal.valueOf(0.2000));

            // Consistency score: Stable outputs (8, 8, 10 pts) -> High consistency (> 0.700)
            assertThat(profile.getConsistencyScore()).isGreaterThan(BigDecimal.valueOf(0.700));

            // Learning rate: Trending upward (5 pts -> 8 pts -> 10 pts) -> Positive slope
            assertThat(profile.getLearningRate()).isGreaterThan(BigDecimal.ZERO);

            assertThat(profile.getLastComputedAt()).isNotNull();
            verify(profileRepository).save(any(DeveloperProfile.class));
        }
    }
}
