package com.sprintiq.pms.modules.tasks.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.tasks.domain.Watcher;
import com.sprintiq.pms.modules.tasks.domain.WatcherRepository;
import com.sprintiq.pms.modules.tasks.web.dto.TaskDtos.WatcherResponse;
import com.sprintiq.pms.modules.users.application.UserService;
import com.sprintiq.pms.modules.users.application.UserSummary;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Subscriptions to item notifications.
 *
 * <p>Lives in the tasks module because the {@code watchers} table is polymorphic and
 * this is where it is written most; the notification fan-out that consumes it arrives in
 * Phase 4, and {@link #watcherIdsFor} is the seam it will read through.</p>
 */
@Service
@RequiredArgsConstructor
public class WatcherService {

    private final WatcherRepository repository;
    private final UserService userService;

    @Transactional(readOnly = true)
    public List<WatcherResponse> list(EntityType entityType, UUID entityId) {
        List<Watcher> watchers = repository.findByEntityTypeAndEntityId(entityType, entityId);
        Map<UUID, UserSummary> people = userService.summariesFor(
                watchers.stream().map(Watcher::getUserId).toList());
        return watchers.stream()
                .map(watcher -> {
                    UserSummary profile = people.get(watcher.getUserId());
                    return new WatcherResponse(watcher.getId(), watcher.getUserId(),
                            profile == null ? null : profile.fullName(),
                            profile == null ? null : profile.avatarUrl(),
                            watcher.getCreatedAt());
                })
                .toList();
    }

    /**
     * Adds a watcher. Idempotent — the UI's "watch" control is a toggle that may be
     * clicked twice, and a duplicate-key error would be an odd thing to show for it.
     */
    @Transactional
    public WatcherResponse add(EntityType entityType, UUID entityId, UUID userId) {
        UUID subject = userId == null ? TenantContext.requireUserId() : userId;
        userService.requireActiveMember(subject);

        Watcher watcher = repository.findByEntityTypeAndEntityIdAndUserId(entityType, entityId, subject)
                .orElseGet(() -> {
                    Watcher created = Watcher.builder()
                            .entityType(entityType).entityId(entityId).userId(subject).build();
                    created.setOrganizationId(TenantContext.requireOrganizationId());
                    return repository.save(created);
                });

        UserSummary profile = userService.summariesFor(List.of(subject)).get(subject);
        return new WatcherResponse(watcher.getId(), subject,
                profile == null ? null : profile.fullName(),
                profile == null ? null : profile.avatarUrl(),
                watcher.getCreatedAt());
    }

    @Transactional
    public void remove(EntityType entityType, UUID entityId, UUID userId) {
        Watcher watcher = repository.findByEntityTypeAndEntityIdAndUserId(entityType, entityId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Watcher", userId));
        repository.delete(watcher);
    }

    /** Recipients for a notification about this item. */
    @Transactional(readOnly = true)
    public List<UUID> watcherIdsFor(EntityType entityType, UUID entityId) {
        return repository.findByEntityTypeAndEntityId(entityType, entityId).stream()
                .map(Watcher::getUserId).toList();
    }
}
