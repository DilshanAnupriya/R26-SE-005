package com.sprintiq.pms.modules.activity.application;

import com.sprintiq.pms.common.web.CorrelationIdFilter;
import com.sprintiq.pms.modules.activity.domain.ActivityAction;
import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.modules.activity.domain.ActivityLog;
import com.sprintiq.pms.modules.activity.domain.ActivityLogRepository;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The delivery modules' single entry point for item history.
 *
 * <p>Unlike {@code AuditService}, these writes are synchronous and join the caller's
 * transaction: history is product data that the user expects to see the moment the
 * PATCH returns, and it should roll back with the change it describes rather than
 * survive as a record of something that never happened.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityService {

    private final ActivityLogRepository repository;

    /** Target of an activity record — the item that changed. */
    public record Target(EntityType type, UUID id, String key, UUID projectId) {

        public static Target of(EntityType type, UUID id, String key, UUID projectId) {
            return new Target(type, id, key, projectId);
        }
    }

    @Transactional
    public void recordCreated(Target target, String summary) {
        save(target, ActivityAction.CREATED, null, null, null, summary, Map.of());
    }

    @Transactional
    public void recordDeleted(Target target, String summary) {
        save(target, ActivityAction.DELETED, null, null, null, summary, Map.of());
    }

    @Transactional
    public void record(Target target, String action, String summary) {
        save(target, action, null, null, null, summary, Map.of());
    }

    @Transactional
    public void record(Target target, String action, String summary, Map<String, Object> metadata) {
        save(target, action, null, null, null, summary, metadata);
    }

    @Transactional
    public void recordChange(Target target, String action, String field, Object oldValue, Object newValue,
                             String summary) {
        save(target, action, field, text(oldValue), text(newValue), summary, Map.of());
    }

    /**
     * Writes one row per changed field. Field-level granularity is what makes the
     * history usable as a diff — a single "updated" row would force the UI to guess.
     */
    @Transactional
    public void recordChanges(Target target, ChangeSet changes) {
        for (ChangeSet.FieldChange change : changes.changes()) {
            save(target, actionFor(change.field()), change.field(), change.oldValue(), change.newValue(),
                    "%s changed".formatted(humanize(change.field())), Map.of());
        }
    }

    @Transactional(readOnly = true)
    public Page<ActivityLog> history(EntityType type, UUID entityId, Pageable pageable) {
        return repository.findByEntityTypeAndEntityId(type.name(), entityId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<ActivityLog> search(UUID projectId, EntityType entityType, UUID entityId, UUID actorId,
                                    Instant from, Instant to, Pageable pageable) {
        return repository.search(TenantContext.requireOrganizationId(), projectId,
                entityType == null ? null : entityType.name(), entityId, actorId, from, to, pageable);
    }

    // ------------------------------------------------------------------ internals

    private void save(Target target, String action, String field, String oldValue, String newValue,
                      String summary, Map<String, Object> metadata) {
        ActivityLog entry = ActivityLog.builder()
                .organizationId(TenantContext.requireOrganizationId())
                .projectId(target.projectId())
                .entityType(target.type().name())
                .entityId(target.id())
                .entityKey(truncateKey(target.key()))
                .actorId(TenantContext.userId().orElse(null))
                .action(action)
                .fieldName(field)
                .oldValue(oldValue)
                .newValue(newValue)
                .summary(truncate(summary))
                .metadata(metadata == null ? Map.of() : metadata)
                .correlationId(CorrelationIdFilter.current())
                .build();
        repository.save(entry);
    }

    /** A few fields get their own verb so the feed can render them distinctly. */
    private static String actionFor(String field) {
        return switch (field) {
            case "status", "workflowStateId" -> ActivityAction.STATUS_CHANGED;
            case "assigneeId" -> ActivityAction.ASSIGNED;
            case "storyPoints", "estimateMinutes" -> ActivityAction.ESTIMATED;
            case "rank" -> ActivityAction.RANKED;
            case "sprintId" -> ActivityAction.MOVED_TO_SPRINT;
            case "blocked" -> ActivityAction.BLOCKED;
            default -> ActivityAction.UPDATED;
        };
    }

    /** {@code targetDate} -> {@code "Target date"}. */
    private static String humanize(String field) {
        String spaced = field.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase();
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static String text(Object value) {
        if (value == null) return null;
        return value instanceof Enum<?> enumValue ? enumValue.name() : String.valueOf(value);
    }

    private static String truncateKey(String key) {
        if (key == null) return null;
        return key.length() > 120 ? key.substring(0, 117) + "..." : key;
    }

    private static String truncate(String summary) {
        if (summary == null) return null;
        return summary.length() > 500 ? summary.substring(0, 497) + "..." : summary;
    }

    /** Convenience for services that need the raw list (project overview, feeds). */
    @Transactional(readOnly = true)
    public List<ActivityLog> recentForProject(UUID projectId, Pageable pageable) {
        return repository.search(TenantContext.requireOrganizationId(), projectId, null, null, null, null, null,
                pageable).getContent();
    }
}
