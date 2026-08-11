package com.sprintiq.pms.modules.projects.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.ConflictException;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.projects.domain.ItemLabel;
import com.sprintiq.pms.modules.projects.domain.ItemLabelRepository;
import com.sprintiq.pms.modules.projects.domain.Label;
import com.sprintiq.pms.modules.projects.domain.LabelRepository;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.*;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Owns labels and their application to work items.
 *
 * <p>Published to the delivery modules so a story or task response can carry its
 * labels without those modules joining the label tables themselves. {@link #labelsFor}
 * is the batch form — a backlog page resolving labels one item at a time is the
 * classic N+1, and the backlog is the highest-traffic read in the product.</p>
 */
@Service
@RequiredArgsConstructor
public class LabelService {

    private final LabelRepository labelRepository;
    private final ItemLabelRepository itemLabelRepository;

    // ------------------------------------------------------------------ catalogue

    /** Project-scoped labels plus the organization-wide ones it inherits. */
    @Transactional(readOnly = true)
    public List<LabelResponse> listForProject(UUID projectId) {
        return labelRepository.findAvailableForProject(projectId).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<LabelResponse> listGlobal() {
        return labelRepository.findByProjectIdIsNullOrderByNameAsc().stream().map(this::toResponse).toList();
    }

    @Transactional
    public LabelResponse create(CreateLabelRequest request) {
        UUID organizationId = TenantContext.requireOrganizationId();
        String name = request.name().trim();

        if (labelRepository.existsByOrganizationIdAndProjectIdAndName(organizationId, request.projectId(), name)) {
            throw ConflictException.duplicate("Label", "name", name);
        }

        Label label = Label.builder()
                .projectId(request.projectId())
                .name(name)
                .color(request.color() == null ? "#64748b" : request.color())
                .build();
        label.setOrganizationId(organizationId);
        return toResponse(labelRepository.save(label));
    }

    @Transactional
    public void delete(UUID labelId) {
        Label label = labelRepository.findById(labelId)
                .orElseThrow(() -> new ResourceNotFoundException("Label", labelId));
        // Detaching first keeps orphaned join rows from surviving the label itself.
        for (EntityType type : EntityType.values()) {
            if (type.isLabelable()) {
                itemLabelRepository.findEntityIdsByLabel(type, labelId)
                        .forEach(entityId -> itemLabelRepository
                                .deleteByLabelIdAndEntityTypeAndEntityId(labelId, type, entityId));
            }
        }
        labelRepository.delete(label);
    }

    // ------------------------------------------------------------------ application

    /**
     * Replaces an item's labels with exactly the supplied set. Declarative rather than
     * add/remove deltas, because the UI is a multi-select: it knows the desired end
     * state, and computing the diff here removes a round of client-side bookkeeping.
     */
    @Transactional
    public List<LabelResponse> apply(EntityType entityType, UUID entityId, List<UUID> labelIds) {
        UUID organizationId = TenantContext.requireOrganizationId();
        Set<UUID> desired = new LinkedHashSet<>(labelIds == null ? List.of() : labelIds);

        List<Label> resolved = desired.isEmpty() ? List.of() : labelRepository.findByIdIn(List.copyOf(desired));
        if (resolved.size() != desired.size()) {
            Set<UUID> found = resolved.stream().map(Label::getId).collect(Collectors.toSet());
            UUID missing = desired.stream().filter(id -> !found.contains(id)).findFirst().orElseThrow();
            throw new ResourceNotFoundException("Label", missing);
        }

        Set<UUID> current = itemLabelRepository.findByEntityTypeAndEntityId(entityType, entityId).stream()
                .map(ItemLabel::getLabelId).collect(Collectors.toCollection(LinkedHashSet::new));

        current.stream().filter(id -> !desired.contains(id)).toList()
                .forEach(id -> itemLabelRepository.deleteByLabelIdAndEntityTypeAndEntityId(id, entityType, entityId));

        for (UUID labelId : desired) {
            if (current.contains(labelId)) continue;
            ItemLabel link = ItemLabel.builder()
                    .labelId(labelId).entityType(entityType).entityId(entityId).build();
            link.setOrganizationId(organizationId);
            itemLabelRepository.save(link);
        }

        return resolved.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<LabelResponse> labelsFor(EntityType entityType, UUID entityId) {
        List<UUID> labelIds = itemLabelRepository.findByEntityTypeAndEntityId(entityType, entityId).stream()
                .map(ItemLabel::getLabelId).toList();
        return labelIds.isEmpty() ? List.of()
                : labelRepository.findByIdIn(labelIds).stream().map(this::toResponse).toList();
    }

    /**
     * Labels for a whole page of items, keyed by item id. Two queries regardless of how
     * many items the page holds.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<LabelResponse>> labelsFor(EntityType entityType, Collection<UUID> entityIds) {
        if (entityIds == null || entityIds.isEmpty()) {
            return Map.of();
        }
        List<ItemLabel> links = itemLabelRepository.findByEntityTypeAndEntityIdIn(entityType, entityIds);
        if (links.isEmpty()) {
            return Map.of();
        }
        Map<UUID, LabelResponse> byLabelId = labelRepository
                .findByIdIn(links.stream().map(ItemLabel::getLabelId).distinct().toList())
                .stream().collect(Collectors.toMap(Label::getId, this::toResponse));

        Map<UUID, List<LabelResponse>> result = new HashMap<>();
        for (ItemLabel link : links) {
            LabelResponse label = byLabelId.get(link.getLabelId());
            if (label != null) {
                result.computeIfAbsent(link.getEntityId(), key -> new ArrayList<>()).add(label);
            }
        }
        return result;
    }

    /** Called when an item is hard-deleted so its join rows do not linger. */
    @Transactional
    public void detachAll(EntityType entityType, UUID entityId) {
        itemLabelRepository.deleteByEntityTypeAndEntityId(entityType, entityId);
    }

    private LabelResponse toResponse(Label label) {
        return new LabelResponse(label.getId(), label.getProjectId(), label.getName(), label.getColor(),
                label.isGlobal());
    }
}
