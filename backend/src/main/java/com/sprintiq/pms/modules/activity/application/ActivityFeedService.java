package com.sprintiq.pms.modules.activity.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.activity.domain.ActivityLog;
import com.sprintiq.pms.modules.activity.web.dto.ActivityDtos.ActivityResponse;
import com.sprintiq.pms.modules.users.application.UserService;
import com.sprintiq.pms.modules.users.application.UserSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The readable side of the activity log.
 *
 * <p>Separate from {@link ActivityService}, which is the write path the delivery modules
 * call. Keeping them apart means the twenty or so call sites that record history do not
 * carry a dependency on the user directory just so a feed can show avatars.</p>
 */
@Service
@RequiredArgsConstructor
public class ActivityFeedService {

    static final Set<String> SORT_FIELDS = Set.of("createdAt", "action", "entityType");

    private final ActivityService activityService;
    private final UserService userService;

    @Transactional(readOnly = true)
    public PageResponse<ActivityResponse> feed(UUID projectId, EntityType entityType, UUID entityId,
                                               UUID actorId, Instant from, Instant to,
                                               PageQuery query) {
        Page<ActivityLog> page = activityService.search(projectId, entityType, entityId, actorId,
                from, to, query.toPageable(SORT_FIELDS, Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResponse.from(page, enrich(page));
    }

    @Transactional(readOnly = true)
    public PageResponse<ActivityResponse> history(EntityType entityType, UUID entityId,
                                                  PageQuery query) {
        Page<ActivityLog> page = activityService.history(entityType, entityId,
                query.toPageable(SORT_FIELDS, Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResponse.from(page, enrich(page));
    }

    /**
     * Resolves every actor on the page in one lookup.
     *
     * <p>A feed of fifty entries by six people is six names, not fifty queries — and the
     * N+1 here would be invisible in development, where the feed has three rows.</p>
     */
    private java.util.function.Function<ActivityLog, ActivityResponse> enrich(Page<ActivityLog> page) {
        Map<UUID, UserSummary> actors = userService.summariesFor(page.getContent().stream()
                .map(ActivityLog::getActorId).filter(Objects::nonNull).toList());

        return log -> {
            UserSummary actor = log.getActorId() == null ? null : actors.get(log.getActorId());
            return ActivityResponse.from(log,
                    actor == null ? null : actor.fullName(),
                    actor == null ? null : actor.avatarUrl());
        };
    }
}
