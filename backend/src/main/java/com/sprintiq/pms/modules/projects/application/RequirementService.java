package com.sprintiq.pms.modules.projects.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.ConflictException;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.activity.application.ActivityService;
import com.sprintiq.pms.modules.activity.application.ChangeSet;
import com.sprintiq.pms.modules.projects.domain.*;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.*;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * The requirements register and its traceability to delivery.
 *
 * <p>Every post-approval edit increments the requirement's change counter. That
 * counter is a model feature, not a statistic: M2 predicts requirement-change risk for
 * a sprint from how much the requirements behind it have already moved, so the count
 * has to be maintained at the moment of the edit.</p>
 */
@Service
@RequiredArgsConstructor
public class RequirementService {

    public static final Set<String> SORT_FIELDS =
            Set.of("referenceCode", "title", "priority", "status", "changeCount", "createdAt", "updatedAt");

    private final RequirementRepository repository;
    private final ProjectLookupService projectLookup;
    private final ActivityService activityService;

    @Transactional(readOnly = true)
    public PageResponse<RequirementResponse> search(UUID projectId, RequirementStatus status, RequirementType type,
                                                    PageQuery query) {
        projectLookup.require(projectId);
        return PageResponse.from(repository.search(projectId, status, type, query.searchTerm(),
                query.toPageable(SORT_FIELDS, Sort.by(Sort.Direction.ASC, "referenceCode"))), this::toResponse);
    }

    @Transactional(readOnly = true)
    public RequirementResponse get(UUID requirementId) {
        return toResponse(require(requirementId));
    }

    @Transactional
    public RequirementResponse create(UUID projectId, CreateRequirementRequest request) {
        ProjectRef project = projectLookup.requireWritable(projectId);

        String referenceCode = (request.referenceCode() == null || request.referenceCode().isBlank())
                ? nextReferenceCode(projectId)
                : request.referenceCode().trim().toUpperCase();

        if (repository.existsByProjectIdAndReferenceCode(projectId, referenceCode)) {
            throw ConflictException.duplicate("Requirement", "referenceCode", referenceCode);
        }

        Requirement requirement = Requirement.builder()
                .projectId(projectId)
                .referenceCode(referenceCode)
                .title(request.title().trim())
                .description(request.description())
                .type(request.type() == null ? RequirementType.FUNCTIONAL : request.type())
                .priority(request.priority() == null ? com.sprintiq.pms.common.domain.Priority.MEDIUM
                        : request.priority())
                .status(request.status() == null ? RequirementStatus.DRAFT : request.status())
                .source(request.source())
                .build();
        requirement.setOrganizationId(TenantContext.requireOrganizationId());
        requirement = repository.save(requirement);

        activityService.recordCreated(target(requirement),
                "Requirement %s created in %s".formatted(referenceCode, project.projectKey()));
        return toResponse(requirement);
    }

    @Transactional
    public RequirementResponse update(UUID requirementId, UpdateRequirementRequest request) {
        Requirement requirement = require(requirementId);
        ChangeSet changes = new ChangeSet();

        changes.apply("title", requirement.getTitle(), trim(request.title()), requirement::setTitle);
        changes.apply("description", requirement.getDescription(), request.description(),
                requirement::setDescription);
        changes.apply("type", requirement.getType(), request.type(), requirement::setType);
        changes.apply("priority", requirement.getPriority(), request.priority(), requirement::setPriority);
        changes.apply("source", requirement.getSource(), request.source(), requirement::setSource);

        // Churn is counted against the status the requirement had *before* this edit:
        // the transition out of DRAFT is the baselining act, not itself a change.
        boolean countsAsChurn = requirement.getStatus().isBaselined() && !changes.isEmpty();

        changes.apply("status", requirement.getStatus(), request.status(), requirement::setStatus);

        if (changes.isEmpty()) {
            return toResponse(requirement);
        }
        if (countsAsChurn) {
            requirement.registerChange();
        }
        requirement = repository.save(requirement);
        activityService.recordChanges(target(requirement), changes);
        return toResponse(requirement);
    }

    @Transactional
    public void delete(UUID requirementId) {
        Requirement requirement = require(requirementId);
        requirement.setDeletedAt(Instant.now());
        repository.save(requirement);
        activityService.recordDeleted(target(requirement),
                "Requirement %s deleted".formatted(requirement.getReferenceCode()));
    }

    /** Total post-approval churn for a project — consumed by the M2 risk feature assembler. */
    @Transactional(readOnly = true)
    public long totalChangeCount(UUID projectId) {
        return repository.totalChangeCount(projectId);
    }

    // ------------------------------------------------------------------ internals

    private Requirement require(UUID requirementId) {
        return repository.findByIdAndDeletedAtIsNull(requirementId)
                .orElseThrow(() -> new ResourceNotFoundException("Requirement", requirementId));
    }

    /**
     * Counts every requirement ever created for the project, including soft-deleted
     * ones, so a deleted REQ-007 is never reissued to a different requirement — the
     * code appears in external documents and has to stay unique for good.
     */
    private String nextReferenceCode(UUID projectId) {
        return "REQ-%03d".formatted(repository.countAllIncludingDeleted(projectId) + 1);
    }

    private ActivityService.Target target(Requirement requirement) {
        return ActivityService.Target.of(EntityType.REQUIREMENT, requirement.getId(),
                requirement.getReferenceCode(), requirement.getProjectId());
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private RequirementResponse toResponse(Requirement requirement) {
        return new RequirementResponse(requirement.getId(), requirement.getProjectId(),
                requirement.getReferenceCode(), requirement.getTitle(), requirement.getDescription(),
                requirement.getType(), requirement.getPriority(), requirement.getStatus(),
                requirement.getSource(), requirement.getChangeCount(), requirement.getLastChangedAt(),
                requirement.getCreatedAt(), requirement.getUpdatedAt());
    }
}
