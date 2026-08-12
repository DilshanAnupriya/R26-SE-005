package com.sprintiq.pms.modules.backlog.application;

import com.sprintiq.pms.common.collaboration.ItemReferenceResolver;
import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.backlog.domain.Epic;
import com.sprintiq.pms.modules.backlog.domain.EpicRepository;
import com.sprintiq.pms.modules.backlog.domain.Story;
import com.sprintiq.pms.modules.backlog.domain.StoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Describes epics and stories to the collaboration features. */
@Component
@RequiredArgsConstructor
public class BacklogItemReferenceResolver implements ItemReferenceResolver {

    private final StoryRepository stories;
    private final EpicRepository epics;

    @Override
    public boolean supports(EntityType entityType) {
        return entityType == EntityType.STORY || entityType == EntityType.EPIC;
    }

    @Override
    @Transactional(readOnly = true)
    public ItemReference resolve(UUID entityId) {
        return stories.findByIdAndDeletedAtIsNull(entityId)
                .map(this::fromStory)
                .orElseGet(() -> epics.findByIdAndDeletedAtIsNull(entityId)
                        .map(this::fromEpic)
                        .orElseThrow(() -> new ResourceNotFoundException("Backlog item", entityId)));
    }

    /**
     * Assignee and reporter are the default audience.
     *
     * <p>Not every project member. A comment on one story in a forty-person programme
     * should reach the two people who own it; anyone else who cares can watch the item
     * explicitly, and that opt-in is what keeps the inbox worth reading.</p>
     */
    private ItemReference fromStory(Story story) {
        List<UUID> interested = new ArrayList<>();
        if (story.getAssigneeId() != null) interested.add(story.getAssigneeId());
        if (story.getReporterId() != null) interested.add(story.getReporterId());

        return new ItemReference(EntityType.STORY, story.getId(), story.getProjectId(),
                story.getItemKey(), story.getTitle(),
                "/projects/%s/stories/%s".formatted(story.getProjectId(), story.getId()),
                interested.stream().filter(Objects::nonNull).distinct().toList());
    }

    private ItemReference fromEpic(Epic epic) {
        return new ItemReference(EntityType.EPIC, epic.getId(), epic.getProjectId(),
                epic.getItemKey(), epic.getTitle(),
                "/projects/%s/epics/%s".formatted(epic.getProjectId(), epic.getId()),
                List.of());
    }
}
