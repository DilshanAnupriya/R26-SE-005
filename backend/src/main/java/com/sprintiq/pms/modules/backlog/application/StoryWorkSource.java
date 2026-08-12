package com.sprintiq.pms.modules.backlog.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.modules.backlog.domain.Story;
import com.sprintiq.pms.modules.backlog.domain.StoryRepository;
import com.sprintiq.pms.modules.backlog.domain.StoryStatus;
import com.sprintiq.pms.modules.roadmap.application.port.InitiativeWorkSource;
import com.sprintiq.pms.modules.roadmap.application.port.LinkedWork;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A single story linked straight to an initiative.
 *
 * <p>Allowed because not every roadmap item decomposes into an epic — a one-story
 * regulatory change is still something a stakeholder wants to see on the plan, and
 * forcing an epic wrapper around it would be bookkeeping for its own sake.</p>
 */
@Component
@RequiredArgsConstructor
public class StoryWorkSource implements InitiativeWorkSource {

    private final StoryRepository storyRepository;

    @Override
    public EntityType supports() {
        return EntityType.STORY;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, LinkedWork> describe(Collection<UUID> entityIds) {
        if (entityIds.isEmpty()) {
            return Map.of();
        }

        return storyRepository.findByIdInAndDeletedAtIsNull(entityIds).stream()
                .collect(Collectors.toMap(Story::getId, StoryWorkSource::describe));
    }

    private static LinkedWork describe(Story story) {
        boolean done = story.getStatus() == StoryStatus.DONE;
        BigDecimal points = story.getStoryPoints() == null ? BigDecimal.ZERO : story.getStoryPoints();

        return new LinkedWork(
                story.getItemKey(),
                story.getTitle(),
                true,
                1,
                done ? 1 : 0,
                points,
                done ? points : BigDecimal.ZERO);
    }
}
