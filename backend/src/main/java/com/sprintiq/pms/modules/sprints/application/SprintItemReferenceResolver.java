package com.sprintiq.pms.modules.sprints.application;

import com.sprintiq.pms.common.collaboration.ItemReferenceResolver;
import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.sprints.domain.Sprint;
import com.sprintiq.pms.modules.sprints.domain.SprintCapacityRepository;
import com.sprintiq.pms.modules.sprints.domain.SprintCapacity;
import com.sprintiq.pms.modules.sprints.domain.SprintRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Describes sprints to the collaboration features.
 *
 * <p>The interested audience is everyone with capacity planned in the sprint — the
 * people actually working it. A risk alert or a cost warning is aimed at them, not at
 * every member of the project.</p>
 */
@Component
@RequiredArgsConstructor
public class SprintItemReferenceResolver implements ItemReferenceResolver {

    private final SprintRepository sprints;
    private final SprintCapacityRepository capacity;

    @Override
    public boolean supports(EntityType entityType) {
        return entityType == EntityType.SPRINT;
    }

    @Override
    @Transactional(readOnly = true)
    public ItemReference resolve(UUID entityId) {
        Sprint sprint = sprints.findByIdAndDeletedAtIsNull(entityId)
                .orElseThrow(() -> new ResourceNotFoundException("Sprint", entityId));

        return new ItemReference(EntityType.SPRINT, sprint.getId(), sprint.getProjectId(),
                null, sprint.getName(),
                "/projects/%s/sprints/%s".formatted(sprint.getProjectId(), sprint.getId()),
                capacity.findBySprintId(entityId).stream()
                        .map(SprintCapacity::getUserId)
                        .distinct()
                        .toList());
    }
}
