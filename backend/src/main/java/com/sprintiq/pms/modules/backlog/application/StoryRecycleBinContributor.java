package com.sprintiq.pms.modules.backlog.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.admin.application.RecycleBinContributor;
import com.sprintiq.pms.modules.admin.application.RecycleBinService;
import com.sprintiq.pms.modules.admin.web.dto.AdminDtos.RecycleBinEntry;
import com.sprintiq.pms.modules.backlog.domain.Story;
import com.sprintiq.pms.modules.backlog.domain.StoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Deleted stories, and their restoration.
 *
 * <p>An item whose project has itself been deleted is listed but not restorable: putting
 * a story back into a project that is in the recycle bin would leave it invisible and
 * confusing. The reason is returned so the admin screen can say why the button is
 * disabled instead of silently greying it out.</p>
 */
@Component
@RequiredArgsConstructor
public class StoryRecycleBinContributor implements RecycleBinContributor {

    private final StoryRepository stories;
    private final StoryService storyService;
    private final com.sprintiq.pms.modules.projects.application.ProjectLookupService projectLookup;

    @Override
    public EntityType entityType() {
        return EntityType.STORY;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<RecycleBinEntry> deletedItems(PageQuery query) {
        return PageResponse.from(stories.findByDeletedAtIsNotNullOrderByDeletedAtDesc(
                PageRequest.of(query.getPage(), query.getSize())), this::toEntry);
    }

    @Override
    @Transactional
    public void restore(UUID entityId) {
        storyService.restore(entityId);
    }

    private RecycleBinEntry toEntry(Story story) {
        boolean projectAlive = projectLookup.exists(story.getProjectId());
        return new RecycleBinEntry(EntityType.STORY, story.getId(), story.getItemKey(),
                story.getTitle(), story.getProjectId(), story.getUpdatedBy(), null,
                story.getDeletedAt(),
                story.getDeletedAt() == null ? null
                        : story.getDeletedAt().plus(RecycleBinService.RETENTION),
                projectAlive,
                projectAlive ? null : "Restore the project first");
    }
}
