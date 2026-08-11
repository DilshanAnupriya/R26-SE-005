package com.sprintiq.pms.modules.projects.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.activity.application.ActivityService;
import com.sprintiq.pms.modules.activity.application.ChangeSet;
import com.sprintiq.pms.modules.projects.domain.Milestone;
import com.sprintiq.pms.modules.projects.domain.MilestoneRepository;
import com.sprintiq.pms.modules.projects.domain.MilestoneStatus;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.*;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Dated checkpoints on the project timeline. */
@Service
@RequiredArgsConstructor
public class MilestoneService {

    private final MilestoneRepository repository;
    private final ProjectLookupService projectLookup;
    private final ActivityService activityService;

    @Transactional(readOnly = true)
    public List<MilestoneResponse> list(UUID projectId) {
        projectLookup.require(projectId);
        return repository.findByProjectIdAndDeletedAtIsNullOrderByDueDateAsc(projectId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public MilestoneResponse get(UUID milestoneId) {
        return toResponse(require(milestoneId));
    }

    @Transactional
    public MilestoneResponse create(UUID projectId, CreateMilestoneRequest request) {
        projectLookup.requireWritable(projectId);

        Milestone milestone = Milestone.builder()
                .projectId(projectId)
                .name(request.name().trim())
                .description(request.description())
                .dueDate(request.dueDate())
                .status(request.status() == null ? MilestoneStatus.PLANNED : request.status())
                .build();
        milestone.setOrganizationId(TenantContext.requireOrganizationId());
        milestone = repository.save(milestone);

        activityService.recordCreated(target(milestone), "Milestone '%s' created".formatted(milestone.getName()));
        return toResponse(milestone);
    }

    @Transactional
    public MilestoneResponse update(UUID milestoneId, UpdateMilestoneRequest request) {
        Milestone milestone = require(milestoneId);
        ChangeSet changes = new ChangeSet();

        changes.apply("name", milestone.getName(), trim(request.name()), milestone::setName);
        changes.apply("description", milestone.getDescription(), request.description(), milestone::setDescription);
        changes.apply("dueDate", milestone.getDueDate(), request.dueDate(), milestone::setDueDate);

        if (request.status() != null && request.status() != milestone.getStatus()) {
            changes.record("status", milestone.getStatus(), request.status());
            milestone.setStatus(request.status());
            // completedAt is derived from the status rather than accepted from the client,
            // so "when was this delivered" always matches the status that says it was.
            milestone.setCompletedAt(request.status() == MilestoneStatus.COMPLETED ? Instant.now() : null);
        }

        if (changes.isEmpty()) {
            return toResponse(milestone);
        }
        milestone = repository.save(milestone);
        activityService.recordChanges(target(milestone), changes);
        return toResponse(milestone);
    }

    @Transactional
    public void delete(UUID milestoneId) {
        Milestone milestone = require(milestoneId);
        milestone.setDeletedAt(Instant.now());
        repository.save(milestone);
        activityService.recordDeleted(target(milestone), "Milestone '%s' deleted".formatted(milestone.getName()));
    }

    // ------------------------------------------------------------------ internals

    private Milestone require(UUID milestoneId) {
        return repository.findByIdAndDeletedAtIsNull(milestoneId)
                .orElseThrow(() -> new ResourceNotFoundException("Milestone", milestoneId));
    }

    private ActivityService.Target target(Milestone milestone) {
        return ActivityService.Target.of(EntityType.MILESTONE, milestone.getId(), null, milestone.getProjectId());
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private MilestoneResponse toResponse(Milestone milestone) {
        return new MilestoneResponse(milestone.getId(), milestone.getProjectId(), milestone.getName(),
                milestone.getDescription(), milestone.getDueDate(), milestone.getStatus(),
                milestone.isOverdue(LocalDate.now()), milestone.getCompletedAt(), milestone.getCreatedAt());
    }
}
