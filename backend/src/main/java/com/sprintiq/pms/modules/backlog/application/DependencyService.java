package com.sprintiq.pms.modules.backlog.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.BusinessRuleException;
import com.sprintiq.pms.common.error.ConflictException;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.activity.application.ActivityService;
import com.sprintiq.pms.modules.activity.domain.ActivityAction;
import com.sprintiq.pms.modules.backlog.domain.*;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.*;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The dependency graph between work items.
 *
 * <p>Two rules make this more than a join table. Edges are stored in one canonical
 * direction, so {@code A blocked_by B} and {@code B blocks A} are the same row rather
 * than two rows that can disagree. And blocking edges are checked for cycles before
 * insertion: a loop in "blocks" is a schedule in which nothing can ever legally
 * start, and it is far cheaper to refuse the edge than to explain the deadlock to a
 * team later.</p>
 */
@Service
@RequiredArgsConstructor
public class DependencyService {

    private final ItemDependencyRepository repository;
    private final StoryRepository storyRepository;
    private final EpicRepository epicRepository;
    private final ActivityService activityService;

    @Transactional(readOnly = true)
    public List<DependencyResponse> list(EntityType sourceType, UUID sourceId) {
        requireItem(sourceType, sourceId);
        return repository.findAllTouching(sourceType, sourceId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public DependencyResponse create(EntityType sourceType, UUID sourceId, CreateDependencyRequest request) {
        requireItem(sourceType, sourceId);
        requireItem(request.targetType(), request.targetId());

        if (sourceType == request.targetType() && sourceId.equals(request.targetId())) {
            throw new BusinessRuleException("An item cannot depend on itself");
        }

        // Normalise to the stored direction so the graph has one edge per relationship.
        EntityType fromType = sourceType;
        UUID fromId = sourceId;
        EntityType toType = request.targetType();
        UUID toId = request.targetId();
        DependencyType type = request.dependencyType();

        if (type.requiresInversion()) {
            fromType = request.targetType();
            fromId = request.targetId();
            toType = sourceType;
            toId = sourceId;
            type = type.inverted();
        }

        if (repository.existsBySourceTypeAndSourceIdAndTargetTypeAndTargetIdAndDependencyType(
                fromType, fromId, toType, toId, type)) {
            throw new ConflictException("That dependency already exists");
        }

        if (type.isBlocking()) {
            assertNoCycle(fromType, fromId, toType, toId);
        }

        ItemDependency dependency = ItemDependency.builder()
                .sourceType(fromType).sourceId(fromId)
                .targetType(toType).targetId(toId)
                .dependencyType(type)
                .build();
        dependency.setOrganizationId(TenantContext.requireOrganizationId());
        dependency = repository.save(dependency);

        activityService.record(targetFor(sourceType, sourceId), ActivityAction.DEPENDENCY_ADDED,
                "%s %s %s".formatted(keyOf(fromType, fromId), type, keyOf(toType, toId)));
        return toResponse(dependency);
    }

    @Transactional
    public void delete(UUID dependencyId) {
        ItemDependency dependency = repository.findById(dependencyId)
                .orElseThrow(() -> new ResourceNotFoundException("Dependency", dependencyId));
        repository.delete(dependency);

        activityService.record(targetFor(dependency.getSourceType(), dependency.getSourceId()),
                ActivityAction.DEPENDENCY_REMOVED,
                "Dependency on %s removed".formatted(keyOf(dependency.getTargetType(),
                        dependency.getTargetId())));
    }

    /** Called when an item is deleted so its edges do not point at a tombstone. */
    @Transactional
    public void detachAll(EntityType type, UUID itemId) {
        repository.deleteAll(repository.findAllTouching(type, itemId));
    }

    // ------------------------------------------------------------------ internals

    /**
     * Refuses an edge {@code from -> to} when {@code to} can already reach {@code from}.
     * The reachability walk happens in the database as a recursive CTE — doing it here
     * would be one query per level of the graph.
     */
    private void assertNoCycle(EntityType fromType, UUID fromId, EntityType toType, UUID toId) {
        boolean wouldCycle = repository.pathExists(TenantContext.requireOrganizationId(),
                toType.name(), toId, fromType.name(), fromId);
        if (wouldCycle) {
            throw new BusinessRuleException(
                    "That dependency would create a cycle: %s already blocks %s, directly or indirectly"
                            .formatted(keyOf(toType, toId), keyOf(fromType, fromId)));
        }
    }

    /** Asserts the item exists and is not deleted, whichever kind it is. */
    private void requireItem(EntityType type, UUID id) {
        boolean exists = switch (type) {
            case STORY -> storyRepository.findByIdAndDeletedAtIsNull(id).isPresent();
            case EPIC -> epicRepository.findByIdAndDeletedAtIsNull(id).isPresent();
            // Tasks are validated by the task module before it calls in; anything else
            // is not a dependency-capable item.
            case TASK -> true;
            default -> throw new BusinessRuleException(
                    "%s items cannot participate in dependencies".formatted(type));
        };
        if (!exists) {
            throw new ResourceNotFoundException(type.name(), id);
        }
    }

    private String keyOf(EntityType type, UUID id) {
        return switch (type) {
            case STORY -> storyRepository.findByIdAndDeletedAtIsNull(id)
                    .map(Story::getItemKey).orElse(id.toString());
            case EPIC -> epicRepository.findByIdAndDeletedAtIsNull(id)
                    .map(Epic::getItemKey).orElse(id.toString());
            default -> id.toString();
        };
    }

    private UUID projectOf(EntityType type, UUID id) {
        return switch (type) {
            case STORY -> storyRepository.findByIdAndDeletedAtIsNull(id).map(Story::getProjectId).orElse(null);
            case EPIC -> epicRepository.findByIdAndDeletedAtIsNull(id).map(Epic::getProjectId).orElse(null);
            default -> null;
        };
    }

    private ActivityService.Target targetFor(EntityType type, UUID id) {
        return ActivityService.Target.of(type, id, keyOf(type, id), projectOf(type, id));
    }

    private DependencyResponse toResponse(ItemDependency dependency) {
        return new DependencyResponse(dependency.getId(),
                dependency.getSourceType(), dependency.getSourceId(),
                keyOf(dependency.getSourceType(), dependency.getSourceId()),
                dependency.getTargetType(), dependency.getTargetId(),
                keyOf(dependency.getTargetType(), dependency.getTargetId()),
                dependency.getDependencyType(), dependency.getCreatedAt());
    }
}
