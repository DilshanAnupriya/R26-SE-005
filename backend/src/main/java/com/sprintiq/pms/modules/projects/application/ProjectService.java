package com.sprintiq.pms.modules.projects.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.ConflictException;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.common.util.SlugGenerator;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.activity.application.ActivityService;
import com.sprintiq.pms.modules.activity.application.ChangeSet;
import com.sprintiq.pms.modules.activity.domain.ActivityAction;
import com.sprintiq.pms.modules.audit.application.AuditService;
import com.sprintiq.pms.modules.audit.domain.AuditAction;
import com.sprintiq.pms.modules.projects.domain.*;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.*;
import com.sprintiq.pms.modules.users.application.UserService;
import com.sprintiq.pms.modules.users.application.UserSummary;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The project aggregate: lifecycle, membership, the overview roll-up and health.
 *
 * <p>Creating a project also seeds its default board columns and enrols its creator as
 * tech lead. Both are done here, inside the same transaction, because a project without
 * columns has no board and a project without members has no assignees — shipping a
 * half-usable project and expecting the client to finish the job is how those steps get
 * skipped.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    public static final Set<String> SORT_FIELDS =
            Set.of("name", "projectKey", "status", "health", "progressPct", "startDate", "targetDate",
                    "createdAt", "updatedAt");

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository memberRepository;
    private final MilestoneRepository milestoneRepository;
    private final RequirementRepository requirementRepository;
    private final WorkflowStateService workflowStateService;
    private final ActivityService activityService;
    private final AuditService auditService;
    private final UserService userService;

    /**
     * Whatever the other delivery modules choose to contribute. Empty on a bare
     * projects-only deployment, which is why the overview tolerates an empty list.
     */
    private final List<ProjectStatisticsContributor> statisticsContributors;

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageResponse<ProjectResponse> search(UUID workspaceId, ProjectStatus status, ProjectHealth health,
                                                UUID leadId, PageQuery query) {
        return PageResponse.from(projectRepository.search(workspaceId, status, health, leadId,
                        query.searchTerm(), query.toPageable(SORT_FIELDS, Sort.by(Sort.Direction.DESC, "updatedAt"))),
                this::toResponse);
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(UUID projectId) {
        return toResponse(require(projectId));
    }

    /**
     * Key and name for a batch of projects, for other modules that need to label one.
     *
     * <p>Published so a roadmap lane or a calendar row never needs
     * {@code ProjectRepository}. Ids that do not resolve are simply absent — a link to
     * a deleted project should leave a lane unlabelled, not fail the page.</p>
     */
    @Transactional(readOnly = true)
    public Map<UUID, ProjectSummary> summariesFor(Collection<UUID> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            return Map.of();
        }
        Set<UUID> distinct = projectIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return projectRepository.findAllById(distinct).stream()
                .filter(project -> project.getDeletedAt() == null)
                .collect(Collectors.toMap(Project::getId,
                        project -> new ProjectSummary(project.getId(), project.getProjectKey(),
                                project.getName())));
    }

    /**
     * Everything the project landing page needs, in one round trip. The per-module
     * counts arrive through {@link ProjectStatisticsContributor} rather than from
     * queries written here, so this module never learns what a story or a sprint is.
     */
    @Transactional(readOnly = true)
    public ProjectOverviewResponse overview(UUID projectId) {
        Project project = require(projectId);
        ProjectStatistics statistics = collectStatistics(projectId);

        // Milestones and requirements belong to this module, so they are read directly
        // rather than routed through the contributor port.
        long requirementCount = requirementRepository.countByProjectIdAndDeletedAtIsNull(projectId);
        long milestoneCount = milestoneRepository
                .findByProjectIdAndDeletedAtIsNullOrderByDueDateAsc(projectId).size();
        long completedMilestones = milestoneRepository
                .countByProjectIdAndStatusAndDeletedAtIsNull(projectId, MilestoneStatus.COMPLETED);

        return new ProjectOverviewResponse(
                toResponse(project),
                statistics.epicCount(),
                statistics.storyCount(),
                statistics.completedStoryCount(),
                statistics.taskCount(),
                statistics.openTaskCount(),
                statistics.blockedCount(),
                requirementCount,
                milestoneCount,
                completedMilestones,
                statistics.totalStoryPoints(),
                statistics.completedStoryPoints(),
                statistics.activeSprintId(),
                statistics.activeSprintName());
    }

    /**
     * Health with the reasoning attached. The stored value is authoritative — a lead may
     * override it — but the signals are recomputed on every read so the badge can always
     * be explained, and so an override that no longer matches reality is visible as a
     * disagreement rather than silently trusted.
     */
    @Transactional(readOnly = true)
    public ProjectHealthResponse health(UUID projectId) {
        Project project = require(projectId);
        ProjectStatistics statistics = collectStatistics(projectId);
        LocalDate today = LocalDate.now();

        List<String> signals = new ArrayList<>();
        Integer daysRemaining = project.getTargetDate() == null
                ? null
                : (int) ChronoUnit.DAYS.between(today, project.getTargetDate());
        boolean overdue = daysRemaining != null && daysRemaining < 0
                && project.getStatus() != ProjectStatus.COMPLETED;

        BigDecimal completion = statistics.completionPct();

        if (overdue) {
            signals.add("Target date passed %d day(s) ago with %s%% of stories complete"
                    .formatted(Math.abs(daysRemaining), completion));
        }
        if (statistics.blockedCount() > 0) {
            signals.add("%d item(s) currently blocked".formatted(statistics.blockedCount()));
        }
        if (statistics.storyCount() > 0 && statistics.activeSprintId() == null
                && project.getStatus() == ProjectStatus.ACTIVE) {
            signals.add("Project is active but no sprint is running");
        }
        // Elapsed-vs-delivered: more than a fifth of the schedule ahead of the burn-up
        // is the point at which a plan stops being recoverable by working harder.
        if (daysRemaining != null && project.getStartDate() != null && statistics.storyCount() > 0) {
            long totalDays = ChronoUnit.DAYS.between(project.getStartDate(), project.getTargetDate());
            if (totalDays > 0) {
                long elapsed = Math.max(0, ChronoUnit.DAYS.between(project.getStartDate(), today));
                BigDecimal elapsedPct = BigDecimal.valueOf(elapsed * 100)
                        .divide(BigDecimal.valueOf(totalDays), 2, java.math.RoundingMode.HALF_UP);
                if (elapsedPct.subtract(completion).compareTo(BigDecimal.valueOf(20)) > 0) {
                    signals.add("%s%% of the schedule elapsed but only %s%% of stories complete"
                            .formatted(elapsedPct, completion));
                }
            }
        }
        if (signals.isEmpty()) {
            signals.add("No risk signals detected");
        }

        return new ProjectHealthResponse(projectId, project.getHealth(), completion, signals, daysRemaining,
                overdue);
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public ProjectResponse create(CreateProjectRequest request) {
        UUID organizationId = TenantContext.requireOrganizationId();
        String projectKey = resolveKey(organizationId, request);

        Project project = Project.builder()
                .workspaceId(request.workspaceId())
                .projectKey(projectKey)
                .name(request.name().trim())
                .description(request.description())
                .status(ProjectStatus.PLANNING)
                .health(ProjectHealth.ON_TRACK)
                .startDate(request.startDate())
                .targetDate(request.targetDate())
                .budgetAmount(request.budgetAmount())
                .currency(request.currency())
                .leadUserId(request.leadUserId())
                .teamId(request.teamId())
                .domain(request.domain())
                .techStack(request.techStack() == null ? new ArrayList<>() : new ArrayList<>(request.techStack()))
                .defaultSprintDays(request.defaultSprintDays() == null ? 14 : request.defaultSprintDays())
                .definitionOfReady(request.definitionOfReady() == null
                        ? new ArrayList<>() : new ArrayList<>(request.definitionOfReady()))
                .definitionOfDone(request.definitionOfDone() == null
                        ? new ArrayList<>() : new ArrayList<>(request.definitionOfDone()))
                .build();
        project.setOrganizationId(organizationId);

        if (request.startDate() != null && request.targetDate() != null
                && request.targetDate().isBefore(request.startDate())) {
            throw new ConflictException("Target date cannot fall before the start date");
        }
        if (request.leadUserId() != null) {
            userService.requireActiveMember(request.leadUserId());
        }

        project = projectRepository.save(project);
        workflowStateService.seedDefaults(project.getId());
        enrolCreator(project, organizationId);

        activityService.recordCreated(target(project), "Project %s created".formatted(projectKey));
        auditService.record(AuditAction.PROJECT_CREATED, "Project", project.getId(), null,
                Map.of("key", projectKey, "name", project.getName(), "workspaceId", request.workspaceId()));
        return toResponse(project);
    }

    @Transactional
    public ProjectResponse update(UUID projectId, UpdateProjectRequest request) {
        Project project = require(projectId);
        ChangeSet changes = new ChangeSet();

        changes.apply("name", project.getName(), trim(request.name()), project::setName);
        changes.apply("description", project.getDescription(), request.description(), project::setDescription);
        changes.apply("startDate", project.getStartDate(), request.startDate(), project::setStartDate);
        changes.apply("targetDate", project.getTargetDate(), request.targetDate(), project::setTargetDate);
        changes.apply("budgetAmount", project.getBudgetAmount(), request.budgetAmount(), project::setBudgetAmount);
        changes.apply("currency", project.getCurrency(), request.currency(), project::setCurrency);
        changes.apply("teamId", project.getTeamId(), request.teamId(), project::setTeamId);
        changes.apply("domain", project.getDomain(), request.domain(), project::setDomain);
        changes.apply("health", project.getHealth(), request.health(), project::setHealth);
        changes.apply("defaultSprintDays", project.getDefaultSprintDays(), request.defaultSprintDays(),
                project::setDefaultSprintDays);

        if (request.leadUserId() != null && !request.leadUserId().equals(project.getLeadUserId())) {
            userService.requireActiveMember(request.leadUserId());
            changes.record("leadUserId", project.getLeadUserId(), request.leadUserId());
            project.setLeadUserId(request.leadUserId());
        }
        if (request.techStack() != null) {
            changes.record("techStack", project.getTechStack(), request.techStack());
            project.setTechStack(new ArrayList<>(request.techStack()));
        }
        if (request.definitionOfReady() != null) {
            project.setDefinitionOfReady(new ArrayList<>(request.definitionOfReady()));
            changes.record("definitionOfReady", null, request.definitionOfReady().size() + " item(s)");
        }
        if (request.definitionOfDone() != null) {
            project.setDefinitionOfDone(new ArrayList<>(request.definitionOfDone()));
            changes.record("definitionOfDone", null, request.definitionOfDone().size() + " item(s)");
        }

        if (request.status() != null && request.status() != project.getStatus()) {
            if (!project.getStatus().canTransitionTo(request.status())) {
                throw ConflictException.illegalTransition("Project", project.getStatus().name(),
                        request.status().name());
            }
            changes.record("status", project.getStatus(), request.status());
            project.setStatus(request.status());
        }

        if (project.getStartDate() != null && project.getTargetDate() != null
                && project.getTargetDate().isBefore(project.getStartDate())) {
            throw new ConflictException("Target date cannot fall before the start date");
        }
        if (changes.isEmpty()) {
            return toResponse(project);
        }

        project = projectRepository.save(project);
        activityService.recordChanges(target(project), changes);
        auditService.record(AuditAction.PROJECT_UPDATED, "Project", projectId,
                changes.beforeState(), changes.afterState());
        return toResponse(project);
    }

    /**
     * Soft delete. The project keeps its rows so the 30-day recycle bin can put it back
     * with its backlog intact — a hard delete here would cascade through every story,
     * task and sprint and could not be undone.
     */
    @Transactional
    public void delete(UUID projectId) {
        Project project = require(projectId);
        project.setDeletedAt(Instant.now());
        projectRepository.save(project);

        activityService.recordDeleted(target(project),
                "Project %s moved to the recycle bin".formatted(project.getProjectKey()));
        auditService.record(AuditAction.PROJECT_DELETED, "Project", projectId,
                Map.of("key", project.getProjectKey(), "name", project.getName()), null);
    }

    @Transactional
    public ProjectResponse restore(UUID projectId) {
        Project project = projectRepository.findByIdAndDeletedAtIsNotNull(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Deleted project", projectId));
        project.setDeletedAt(null);
        project = projectRepository.save(project);

        activityService.record(target(project), ActivityAction.RESTORED,
                "Project %s restored".formatted(project.getProjectKey()));
        return toResponse(project);
    }

    @Transactional(readOnly = true)
    public PageResponse<ProjectResponse> listDeleted(PageQuery query) {
        return PageResponse.from(projectRepository.findByDeletedAtIsNotNull(
                query.toPageable(SORT_FIELDS, Sort.by(Sort.Direction.DESC, "updatedAt"))), this::toResponse);
    }

    // ------------------------------------------------------------------ members

    @Transactional(readOnly = true)
    public List<ProjectMemberResponse> listMembers(UUID projectId) {
        require(projectId);
        List<ProjectMember> members = memberRepository.findByProjectId(projectId);
        Map<UUID, UserSummary> profiles = userService.summariesFor(
                members.stream().map(ProjectMember::getUserId).toList());
        return members.stream().map(member -> toResponse(member, profiles.get(member.getUserId()))).toList();
    }

    @Transactional
    public ProjectMemberResponse addMember(UUID projectId, AddProjectMemberRequest request) {
        Project project = require(projectId);
        userService.requireActiveMember(request.userId());

        if (memberRepository.existsByProjectIdAndUserId(projectId, request.userId())) {
            throw new ConflictException("User is already a member of this project");
        }

        ProjectRole role = request.projectRole() == null ? ProjectRole.DEVELOPER : request.projectRole();
        ProjectMember member = ProjectMember.builder()
                .projectId(projectId)
                .userId(request.userId())
                .projectRole(role)
                // A STAKEHOLDER role implies the flag; the flag can also be set on any
                // role for someone who observes without being planned against.
                .stakeholder(Boolean.TRUE.equals(request.stakeholder()) || role == ProjectRole.STAKEHOLDER)
                .allocationPct(request.allocationPct() == null ? 100 : request.allocationPct())
                .build();
        member.setOrganizationId(project.getOrganizationId());
        member = memberRepository.save(member);

        activityService.record(target(project),
                ActivityAction.UPDATED,
                "Member added to %s as %s".formatted(project.getProjectKey(), role));
        return toResponse(member, userService.summariesFor(List.of(request.userId())).get(request.userId()));
    }

    @Transactional
    public ProjectMemberResponse updateMember(UUID projectId, UUID userId, UpdateProjectMemberRequest request) {
        require(projectId);
        ProjectMember member = memberRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Project member", userId));

        if (request.projectRole() != null) member.setProjectRole(request.projectRole());
        if (request.stakeholder() != null) member.setStakeholder(request.stakeholder());
        if (request.allocationPct() != null) member.setAllocationPct(request.allocationPct());

        member = memberRepository.save(member);
        return toResponse(member, userService.summariesFor(List.of(userId)).get(userId));
    }

    @Transactional
    public void removeMember(UUID projectId, UUID userId) {
        Project project = require(projectId);
        if (!memberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            throw new ResourceNotFoundException("Project member", userId);
        }
        if (userId.equals(project.getLeadUserId())) {
            throw new ConflictException(
                    "Reassign the project lead before removing them from the project");
        }
        memberRepository.deleteByProjectIdAndUserId(projectId, userId);
        activityService.record(target(project),
                ActivityAction.UPDATED,
                "Member removed from %s".formatted(project.getProjectKey()));
    }

    // ------------------------------------------------------------------ internals

    private Project require(UUID projectId) {
        return projectRepository.findByIdAndDeletedAtIsNull(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
    }

    private ProjectStatistics collectStatistics(UUID projectId) {
        ProjectStatistics total = ProjectStatistics.empty();
        for (ProjectStatisticsContributor contributor : statisticsContributors) {
            total = total.merge(contributor.contribute(projectId));
        }
        return total;
    }

    /**
     * Derives a project key from the name when the client did not supply one, and makes
     * it unique inside the tenant. The key prefixes every item in the project and is
     * immutable afterwards, so a collision has to be resolved now rather than surfaced
     * as a constraint violation.
     */
    private String resolveKey(UUID organizationId, CreateProjectRequest request) {
        String base = (request.projectKey() == null || request.projectKey().isBlank())
                ? SlugGenerator.toKey(request.name(), 6)
                : request.projectKey().toUpperCase(Locale.ROOT);

        if (request.projectKey() != null && !request.projectKey().isBlank()) {
            if (projectRepository.existsByOrganizationIdAndProjectKey(organizationId, base)) {
                throw ConflictException.duplicate("Project", "key", base);
            }
            return base;
        }

        String candidate = base;
        int suffix = 2;
        while (projectRepository.existsByOrganizationIdAndProjectKey(organizationId, candidate)) {
            candidate = base.substring(0, Math.min(base.length(), 8)) + suffix++;
            if (suffix > 999) {
                throw new ConflictException("Unable to derive a unique project key from '%s'"
                        .formatted(request.name()));
            }
        }
        return candidate;
    }

    /**
     * The creator (or nominated lead) joins as tech lead. Without this the project has
     * no members, so nothing can be assigned and the capacity meter starts at zero.
     */
    private void enrolCreator(Project project, UUID organizationId) {
        UUID leadId = project.getLeadUserId() != null
                ? project.getLeadUserId()
                : TenantContext.userId().orElse(null);
        if (leadId == null) {
            return;
        }
        ProjectMember member = ProjectMember.builder()
                .projectId(project.getId())
                .userId(leadId)
                .projectRole(ProjectRole.TECH_LEAD)
                .allocationPct((short) 100)
                .build();
        member.setOrganizationId(organizationId);
        memberRepository.save(member);
    }

    private ActivityService.Target target(Project project) {
        return ActivityService.Target.of(EntityType.PROJECT, project.getId(), project.getProjectKey(),
                project.getId());
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private ProjectResponse toResponse(Project project) {
        return new ProjectResponse(project.getId(), project.getWorkspaceId(), project.getProjectKey(),
                project.getName(), project.getDescription(), project.getStatus(), project.getHealth(),
                project.getProgressPct(), project.getStartDate(), project.getTargetDate(),
                project.getBudgetAmount(), project.getCurrency(), project.getLeadUserId(), project.getTeamId(),
                project.getDomain(), project.getTechStack(), project.getDefaultSprintDays(),
                project.getDefinitionOfReady(), project.getDefinitionOfDone(),
                memberRepository.countByProjectId(project.getId()),
                project.getCreatedAt(), project.getUpdatedAt());
    }

    private ProjectMemberResponse toResponse(ProjectMember member, UserSummary profile) {
        return new ProjectMemberResponse(member.getId(), member.getProjectId(), member.getUserId(),
                profile == null ? null : profile.fullName(),
                profile == null ? null : profile.email(),
                profile == null ? null : profile.avatarUrl(),
                member.getProjectRole(), member.isStakeholder(), member.getAllocationPct(),
                member.getCreatedAt());
    }
}
