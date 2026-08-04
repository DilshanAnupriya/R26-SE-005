package com.sprintiq.pms.modules.projects.web.dto;

import com.sprintiq.pms.common.domain.Priority;
import com.sprintiq.pms.modules.projects.domain.*;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Request and response records for the projects module.
 *
 * <p>Grouped in one file per module, matching the convention set by
 * {@code OrganizationDtos}: these types are only ever read alongside each other, and
 * one file keeps a request and its response from drifting apart.</p>
 *
 * <p>PATCH requests use nullable fields where {@code null} means "not supplied". Fields
 * a client is allowed to clear carry an explicit boolean companion, because {@code null}
 * cannot express both "leave it" and "empty it".</p>
 */
public final class ProjectDtos {

    private ProjectDtos() {}

    // ---------------------------------------------------------------- projects

    @Schema(name = "CreateProjectRequest")
    public record CreateProjectRequest(
            @NotNull UUID workspaceId,
            @NotBlank @Size(max = 150) String name,
            @Size(max = 10) @Pattern(regexp = "^[A-Za-z][A-Za-z0-9]*$",
                    message = "must start with a letter and contain only letters and digits")
            String projectKey,
            @Size(max = 5000) String description,
            LocalDate startDate,
            LocalDate targetDate,
            @DecimalMin("0.0") BigDecimal budgetAmount,
            @Size(max = 3) String currency,
            UUID leadUserId,
            UUID teamId,
            @Size(max = 80) String domain,
            List<@Size(max = 60) String> techStack,
            @Min(1) @Max(60) Short defaultSprintDays,
            List<@Size(max = 255) String> definitionOfReady,
            List<@Size(max = 255) String> definitionOfDone) {}

    @Schema(name = "UpdateProjectRequest")
    public record UpdateProjectRequest(
            @Size(max = 150) String name,
            @Size(max = 5000) String description,
            ProjectStatus status,
            ProjectHealth health,
            LocalDate startDate,
            LocalDate targetDate,
            @DecimalMin("0.0") BigDecimal budgetAmount,
            @Size(max = 3) String currency,
            UUID leadUserId,
            UUID teamId,
            @Size(max = 80) String domain,
            List<@Size(max = 60) String> techStack,
            @Min(1) @Max(60) Short defaultSprintDays,
            List<@Size(max = 255) String> definitionOfReady,
            List<@Size(max = 255) String> definitionOfDone) {}

    @Schema(name = "ProjectResponse")
    public record ProjectResponse(
            UUID id,
            UUID workspaceId,
            String projectKey,
            String name,
            String description,
            ProjectStatus status,
            ProjectHealth health,
            BigDecimal progressPct,
            LocalDate startDate,
            LocalDate targetDate,
            BigDecimal budgetAmount,
            String currency,
            UUID leadUserId,
            UUID teamId,
            String domain,
            List<String> techStack,
            short defaultSprintDays,
            List<String> definitionOfReady,
            List<String> definitionOfDone,
            long memberCount,
            Instant createdAt,
            Instant updatedAt) {}

    /** Aggregated counts for the project landing page — one call instead of six. */
    @Schema(name = "ProjectOverviewResponse")
    public record ProjectOverviewResponse(
            ProjectResponse project,
            long epicCount,
            long storyCount,
            long completedStoryCount,
            long taskCount,
            long openTaskCount,
            long blockedCount,
            long requirementCount,
            long milestoneCount,
            long completedMilestoneCount,
            BigDecimal totalStoryPoints,
            BigDecimal completedStoryPoints,
            UUID activeSprintId,
            String activeSprintName) {}

    /** Health with the reasoning attached, so the UI can explain an AT_RISK badge. */
    @Schema(name = "ProjectHealthResponse")
    public record ProjectHealthResponse(
            UUID projectId,
            ProjectHealth health,
            BigDecimal progressPct,
            List<String> signals,
            Integer daysRemaining,
            boolean overdue) {}

    // ---------------------------------------------------------------- members

    @Schema(name = "AddProjectMemberRequest")
    public record AddProjectMemberRequest(
            @NotNull UUID userId,
            ProjectRole projectRole,
            Boolean stakeholder,
            @Min(0) @Max(100) Short allocationPct) {}

    @Schema(name = "UpdateProjectMemberRequest")
    public record UpdateProjectMemberRequest(
            ProjectRole projectRole,
            Boolean stakeholder,
            @Min(0) @Max(100) Short allocationPct) {}

    @Schema(name = "ProjectMemberResponse")
    public record ProjectMemberResponse(
            UUID id,
            UUID projectId,
            UUID userId,
            String fullName,
            String email,
            String avatarUrl,
            ProjectRole projectRole,
            boolean stakeholder,
            short allocationPct,
            Instant createdAt) {}

    // ------------------------------------------------------------- milestones

    @Schema(name = "CreateMilestoneRequest")
    public record CreateMilestoneRequest(
            @NotBlank @Size(max = 150) String name,
            @Size(max = 5000) String description,
            LocalDate dueDate,
            MilestoneStatus status) {}

    @Schema(name = "UpdateMilestoneRequest")
    public record UpdateMilestoneRequest(
            @Size(max = 150) String name,
            @Size(max = 5000) String description,
            LocalDate dueDate,
            MilestoneStatus status) {}

    @Schema(name = "MilestoneResponse")
    public record MilestoneResponse(
            UUID id,
            UUID projectId,
            String name,
            String description,
            LocalDate dueDate,
            MilestoneStatus status,
            boolean overdue,
            Instant completedAt,
            Instant createdAt) {}

    // ----------------------------------------------------------- requirements

    @Schema(name = "CreateRequirementRequest")
    public record CreateRequirementRequest(
            @Size(max = 32) String referenceCode,
            @NotBlank @Size(max = 255) String title,
            @Size(max = 10000) String description,
            RequirementType type,
            Priority priority,
            RequirementStatus status,
            @Size(max = 120) String source) {}

    @Schema(name = "UpdateRequirementRequest")
    public record UpdateRequirementRequest(
            @Size(max = 255) String title,
            @Size(max = 10000) String description,
            RequirementType type,
            Priority priority,
            RequirementStatus status,
            @Size(max = 120) String source) {}

    @Schema(name = "RequirementResponse")
    public record RequirementResponse(
            UUID id,
            UUID projectId,
            String referenceCode,
            String title,
            String description,
            RequirementType type,
            Priority priority,
            RequirementStatus status,
            String source,
            int changeCount,
            Instant lastChangedAt,
            Instant createdAt,
            Instant updatedAt) {}

    // -------------------------------------------------------- workflow states

    @Schema(name = "CreateWorkflowStateRequest")
    public record CreateWorkflowStateRequest(
            @NotBlank @Size(max = 60) String name,
            @Size(max = 40) @Pattern(regexp = "^[A-Z][A-Z0-9_]*$",
                    message = "must be UPPER_SNAKE_CASE") String code,
            @NotNull WorkflowCategory category,
            @Min(0) @Max(100) Short position,
            @Min(1) @Max(999) Short wipLimit,
            @Size(max = 16) String color) {}

    @Schema(name = "UpdateWorkflowStateRequest")
    public record UpdateWorkflowStateRequest(
            @Size(max = 60) String name,
            WorkflowCategory category,
            @Min(0) @Max(100) Short position,
            @Min(0) @Max(999) Short wipLimit,
            @Size(max = 16) String color) {}

    @Schema(name = "WorkflowStateResponse")
    public record WorkflowStateResponse(
            UUID id,
            UUID projectId,
            String name,
            String code,
            WorkflowCategory category,
            short position,
            Short wipLimit,
            String color) {}

    /** Reorder in one call — sending columns one at a time would show a jumbled board. */
    @Schema(name = "ReorderWorkflowStatesRequest")
    public record ReorderWorkflowStatesRequest(
            @NotEmpty List<@NotNull UUID> orderedStateIds) {}

    // ------------------------------------------------------------------ labels

    @Schema(name = "CreateLabelRequest")
    public record CreateLabelRequest(
            @NotBlank @Size(max = 60) String name,
            @Size(max = 16) @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "must be a hex colour like #64748b")
            String color,
            @Schema(description = "Omit to create an organization-wide label") UUID projectId) {}

    @Schema(name = "LabelResponse")
    public record LabelResponse(
            UUID id,
            UUID projectId,
            String name,
            String color,
            boolean global) {}

    @Schema(name = "ApplyLabelsRequest")
    public record ApplyLabelsRequest(@NotNull List<@NotNull UUID> labelIds) {}
}
