package com.sprintiq.pms.modules.activity.web.dto;

import com.sprintiq.pms.modules.activity.domain.ActivityLog;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public final class ActivityDtos {

    private ActivityDtos() {
    }

    /**
     * One entry in the feed.
     *
     * <p>{@code summary} is the sentence written when the change happened, not one
     * rendered now. An item renamed twice since then would otherwise have its history
     * silently rewritten to describe the current name.</p>
     */
    @Schema(name = "ActivityResponse")
    public record ActivityResponse(UUID id,
                                   String entityType,
                                   UUID entityId,
                                   String entityKey,
                                   UUID projectId,
                                   String action,
                                   String fieldName,
                                   String oldValue,
                                   String newValue,
                                   String summary,
                                   UUID actorId,
                                   String actorName,
                                   String actorAvatarUrl,
                                   Instant createdAt) {

        public static ActivityResponse from(ActivityLog log, String actorName, String actorAvatarUrl) {
            return new ActivityResponse(log.getId(), log.getEntityType(), log.getEntityId(),
                    log.getEntityKey(), log.getProjectId(), log.getAction(), log.getFieldName(),
                    log.getOldValue(), log.getNewValue(), log.getSummary(), log.getActorId(),
                    actorName, actorAvatarUrl, log.getCreatedAt());
        }
    }
}
