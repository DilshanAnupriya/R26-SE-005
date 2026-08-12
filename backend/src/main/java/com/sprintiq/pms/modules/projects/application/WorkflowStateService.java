package com.sprintiq.pms.modules.projects.application;

import com.sprintiq.pms.common.error.ConflictException;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.projects.domain.WorkflowCategory;
import com.sprintiq.pms.modules.projects.domain.WorkflowState;
import com.sprintiq.pms.modules.projects.domain.WorkflowStateRepository;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.*;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Owns each project's board columns.
 *
 * <p>Also the publisher of {@link WorkflowStateView} for the backlog and task modules,
 * which need to place a new item in the project's first TODO column and to render the
 * board — neither of which should require them to know how columns are stored.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowStateService {

    /**
     * Seeded on project creation. A project with no columns has no board, and asking a
     * user to define a workflow before they can create their first story is a poor
     * first five minutes — these four cover the common case and are fully editable.
     */
    private record DefaultState(String name, String code, WorkflowCategory category, String color) {}

    private static final List<DefaultState> DEFAULT_STATES = List.of(
            new DefaultState("To Do", "TODO", WorkflowCategory.TODO, "#94a3b8"),
            new DefaultState("In Progress", "IN_PROGRESS", WorkflowCategory.IN_PROGRESS, "#3b82f6"),
            new DefaultState("In Review", "IN_REVIEW", WorkflowCategory.IN_PROGRESS, "#a855f7"),
            new DefaultState("Done", "DONE", WorkflowCategory.DONE, "#22c55e"));

    private final WorkflowStateRepository repository;
    private final ProjectLookupService projectLookup;

    /** Whichever delivery modules are deployed; empty is valid and means "nothing occupies columns". */
    private final List<WorkflowStateUsageCounter> usageCounters;

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public List<WorkflowStateResponse> list(UUID projectId) {
        projectLookup.require(projectId);
        return repository.findByProjectIdOrderByPositionAsc(projectId).stream().map(this::toResponse).toList();
    }

    /** Published to the delivery modules for board rendering and status resolution. */
    @Transactional(readOnly = true)
    public List<WorkflowStateView> viewsFor(UUID projectId) {
        return repository.findByProjectIdOrderByPositionAsc(projectId).stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public WorkflowStateView requireView(UUID projectId, UUID stateId) {
        WorkflowState state = repository.findById(stateId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowState", stateId));
        if (!state.getProjectId().equals(projectId)) {
            throw new ConflictException("Workflow state %s does not belong to project %s"
                    .formatted(stateId, projectId));
        }
        return toView(state);
    }

    /**
     * The column a newly created item lands in. Falls back to the lowest-positioned
     * column when a project has no TODO category left — a team is allowed to delete it,
     * and a create should not fail because of that.
     */
    @Transactional(readOnly = true)
    public UUID defaultStateId(UUID projectId) {
        return repository.findFirstByProjectIdAndCategoryOrderByPositionAsc(projectId, WorkflowCategory.TODO)
                .or(() -> repository.findByProjectIdOrderByPositionAsc(projectId).stream().findFirst())
                .map(WorkflowState::getId)
                .orElse(null);
    }

    // ------------------------------------------------------------------ commands

    /** Called once, when a project is created. */
    @Transactional
    public void seedDefaults(UUID projectId) {
        UUID organizationId = TenantContext.requireOrganizationId();
        short position = 0;
        for (DefaultState template : DEFAULT_STATES) {
            WorkflowState state = WorkflowState.builder()
                    .projectId(projectId)
                    .name(template.name())
                    .code(template.code())
                    .category(template.category())
                    .position(position++)
                    .color(template.color())
                    .build();
            state.setOrganizationId(organizationId);
            repository.save(state);
        }
    }

    @Transactional
    public WorkflowStateResponse create(UUID projectId, CreateWorkflowStateRequest request) {
        projectLookup.require(projectId);
        String code = (request.code() == null || request.code().isBlank())
                ? toCode(request.name())
                : request.code().toUpperCase(Locale.ROOT);

        if (repository.existsByProjectIdAndCode(projectId, code)) {
            throw ConflictException.duplicate("Workflow state", "code", code);
        }

        short position = request.position() != null
                ? request.position()
                : (short) repository.countByProjectId(projectId);

        WorkflowState state = WorkflowState.builder()
                .projectId(projectId)
                .name(request.name().trim())
                .code(code)
                .category(request.category())
                .position(position)
                .wipLimit(request.wipLimit())
                .color(request.color())
                .build();
        state.setOrganizationId(TenantContext.requireOrganizationId());
        return toResponse(repository.save(state));
    }

    @Transactional
    public WorkflowStateResponse update(UUID stateId, UpdateWorkflowStateRequest request) {
        WorkflowState state = require(stateId);
        if (request.name() != null) state.setName(request.name().trim());
        if (request.category() != null) state.setCategory(request.category());
        if (request.position() != null) state.setPosition(request.position());
        if (request.color() != null) state.setColor(request.color());
        // 0 is the client's way of clearing a WIP limit; the column stays, the cap goes.
        if (request.wipLimit() != null) {
            state.setWipLimit(request.wipLimit() == 0 ? null : request.wipLimit());
        }
        return toResponse(repository.save(state));
    }

    /**
     * Applies a whole new column order in one transaction. Positions are rewritten from
     * the supplied sequence rather than patched individually, so a partially applied
     * reorder cannot leave two columns claiming the same slot.
     */
    @Transactional
    public List<WorkflowStateResponse> reorder(UUID projectId, ReorderWorkflowStatesRequest request) {
        List<WorkflowState> states = repository.findByProjectIdOrderByPositionAsc(projectId);
        if (states.size() != request.orderedStateIds().size()
                || !states.stream().map(WorkflowState::getId).allMatch(request.orderedStateIds()::contains)) {
            throw new ConflictException(
                    "Reorder must list every workflow state in the project exactly once");
        }

        short position = 0;
        for (UUID stateId : request.orderedStateIds()) {
            WorkflowState state = states.stream().filter(s -> s.getId().equals(stateId)).findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("WorkflowState", stateId));
            state.setPosition(position++);
        }
        return repository.saveAll(states).stream()
                .sorted(java.util.Comparator.comparing(WorkflowState::getPosition))
                .map(this::toResponse).toList();
    }

    /**
     * Deleting a column would orphan every item sitting in it, so the caller has to
     * empty it first. The occupancy count comes from the modules that own the items —
     * see {@link WorkflowStateUsageCounter}.
     */
    @Transactional
    public void delete(UUID stateId) {
        WorkflowState state = require(stateId);
        long itemsInState = countItemsIn(stateId);
        if (itemsInState > 0) {
            throw new ConflictException(
                    "Workflow state '%s' still holds %d item(s); move them before deleting the column"
                            .formatted(state.getName(), itemsInState));
        }
        if (repository.countByProjectId(state.getProjectId()) <= 1) {
            throw new ConflictException("A project must keep at least one workflow state");
        }
        repository.delete(state);
    }

    // ------------------------------------------------------------------ internals

    private WorkflowState require(UUID stateId) {
        return repository.findById(stateId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowState", stateId));
    }

    /** Total live items across every module currently sitting in one column. */
    @Transactional(readOnly = true)
    public long countItemsIn(UUID stateId) {
        return usageCounters.stream().mapToLong(counter -> counter.countItemsInState(stateId)).sum();
    }

    /** {@code "Ready for QA"} -> {@code READY_FOR_QA}. */
    private static String toCode(String name) {
        String code = name.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("(^_+)|(_+$)", "");
        if (code.isBlank()) code = "STATE";
        if (!Character.isLetter(code.charAt(0))) code = "S" + code;
        return code.substring(0, Math.min(code.length(), 40));
    }

    private WorkflowStateResponse toResponse(WorkflowState state) {
        return new WorkflowStateResponse(state.getId(), state.getProjectId(), state.getName(), state.getCode(),
                state.getCategory(), state.getPosition(), state.getWipLimit(), state.getColor());
    }

    private WorkflowStateView toView(WorkflowState state) {
        return new WorkflowStateView(state.getId(), state.getProjectId(), state.getName(), state.getCode(),
                state.getCategory().name(), state.getPosition(), state.getWipLimit(), state.getColor());
    }
}
