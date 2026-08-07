package com.sprintiq.pms.modules.ai.application.port;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.domain.Priority;

import java.util.List;
import java.util.UUID;

/**
 * The item an allocation is being ranked for, described in the terms Model 3 needs:
 * what it is, how big, what it touches and who currently holds it.
 *
 * <p>Model 3 ranks people for <em>a task or a story</em>. Rather than two near-identical
 * ports, the owning modules each describe their item in the same shape — the ranking
 * logic does not care which table the row came from, only whether the work needs
 * TypeScript and how urgent it is.</p>
 */
public interface WorkItemFeatureSource {

    record WorkItemFeatures(UUID itemId,
                            EntityType entityType,
                            UUID projectId,
                            UUID sprintId,
                            String itemKey,
                            String title,
                            String type,
                            Priority priority,
                            Integer estimatedMinutes,
                            java.math.BigDecimal storyPoints,
                            /** Label names, lower-cased — matched against developer skills. */
                            List<String> skillTags,
                            UUID currentAssigneeId,
                            boolean blocked) {}

    /** True when this source owns the given entity type. */
    boolean supports(EntityType entityType);

    /**
     * Named {@code workItemFor} rather than {@code featuresFor} because the backlog
     * module implements this interface alongside {@link StoryFeatureSource}, and two
     * {@code featuresFor(UUID)} methods returning different records cannot coexist on
     * one class.
     */
    WorkItemFeatures workItemFor(UUID itemId);
}
