package com.sprintiq.pms.modules.sprints.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.activity.application.ActivityService;
import com.sprintiq.pms.modules.activity.domain.ActivityAction;
import com.sprintiq.pms.modules.sprints.domain.Retrospective;
import com.sprintiq.pms.modules.sprints.domain.RetrospectiveRepository;
import com.sprintiq.pms.modules.sprints.domain.Sprint;
import com.sprintiq.pms.modules.sprints.domain.SprintRepository;
import com.sprintiq.pms.modules.sprints.web.dto.SprintDtos.*;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The sprint retrospective. One per sprint, created on first save.
 *
 * <p>Upserted rather than created-then-updated, because a retrospective is written live
 * during the meeting: the first person to add a note should not have to have pressed
 * "create" first, and the client should not have to know whether one exists yet.</p>
 */
@Service
@RequiredArgsConstructor
public class RetrospectiveService {

    private final RetrospectiveRepository repository;
    private final SprintRepository sprintRepository;
    private final ActivityService activityService;

    @Transactional(readOnly = true)
    public RetrospectiveResponse get(UUID sprintId) {
        requireSprint(sprintId);
        return repository.findBySprintId(sprintId)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Retrospective for sprint", sprintId));
    }

    @Transactional
    public RetrospectiveResponse save(UUID sprintId, RetrospectiveRequest request) {
        Sprint sprint = requireSprint(sprintId);
        boolean isNew = repository.findBySprintId(sprintId).isEmpty();

        Retrospective retrospective = repository.findBySprintId(sprintId).orElseGet(() -> {
            Retrospective created = Retrospective.builder().sprintId(sprintId).build();
            created.setOrganizationId(TenantContext.requireOrganizationId());
            return created;
        });

        if (request.wentWell() != null) retrospective.setWentWell(new ArrayList<>(request.wentWell()));
        if (request.wentWrong() != null) retrospective.setWentWrong(new ArrayList<>(request.wentWrong()));
        if (request.actionItems() != null) {
            retrospective.setActionItems(normaliseActionItems(request.actionItems()));
        }
        if (request.teamMood() != null) retrospective.setTeamMood(request.teamMood());
        if (request.summary() != null) retrospective.setSummary(request.summary());
        if (request.facilitatorId() != null) {
            retrospective.setFacilitatorId(request.facilitatorId());
        } else if (retrospective.getFacilitatorId() == null) {
            retrospective.setFacilitatorId(TenantContext.userId().orElse(null));
        }

        retrospective = repository.save(retrospective);

        activityService.record(
                ActivityService.Target.of(EntityType.SPRINT, sprint.getId(), sprint.getName(),
                        sprint.getProjectId()),
                isNew ? ActivityAction.CREATED : ActivityAction.UPDATED,
                "Retrospective for %s %s".formatted(sprint.getName(), isNew ? "created" : "updated"));
        return toResponse(retrospective);
    }

    // ------------------------------------------------------------------ internals

    /**
     * Gives every action item the same shape. Clients send free-form maps, and an item
     * without a {@code done} flag would be invisible to the "what did we follow through
     * on" view that makes retrospectives worth holding.
     */
    private List<Map<String, Object>> normaliseActionItems(List<Map<String, Object>> items) {
        List<Map<String, Object>> normalised = new ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            Map<String, Object> entry = new java.util.LinkedHashMap<>(item);
            entry.putIfAbsent("text", "");
            entry.putIfAbsent("ownerId", null);
            entry.putIfAbsent("done", Boolean.FALSE);
            normalised.add(entry);
        }
        return normalised;
    }

    private Sprint requireSprint(UUID sprintId) {
        return sprintRepository.findByIdAndDeletedAtIsNull(sprintId)
                .orElseThrow(() -> new ResourceNotFoundException("Sprint", sprintId));
    }

    private RetrospectiveResponse toResponse(Retrospective retrospective) {
        return new RetrospectiveResponse(retrospective.getId(), retrospective.getSprintId(),
                retrospective.getWentWell(), retrospective.getWentWrong(), retrospective.getActionItems(),
                retrospective.getTeamMood(), retrospective.getSummary(), retrospective.getFacilitatorId(),
                retrospective.getCreatedAt(), retrospective.getUpdatedAt());
    }
}
