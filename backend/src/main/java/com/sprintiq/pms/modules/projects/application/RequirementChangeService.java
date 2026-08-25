package com.sprintiq.pms.modules.projects.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.activity.application.ActivityService;
import com.sprintiq.pms.modules.ai.application.SprintRiskAdjustmentService;
import com.sprintiq.pms.modules.ai.domain.RiskType;
import com.sprintiq.pms.modules.projects.domain.*;
import com.sprintiq.pms.modules.projects.web.dto.RequirementChangeDtos.*;
import com.sprintiq.pms.modules.users.application.UserService;
import com.sprintiq.pms.modules.users.application.UserSummary;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The requirement-change register.
 *
 * <p>Logging a change does three things at once, and they have to happen together or
 * the register becomes a diary nothing reads: the change is recorded, the requirement's
 * churn counter moves (which is a model feature, not a statistic), and the sprint that
 * was in flight is charged the points the change is worth.</p>
 *
 * <p>The charge goes through the AI module's published adjustment ledger rather than
 * being applied to a score directly. The layering rules forbid the AI module from
 * reaching in here to notice a change, so the module that observes the event is the one
 * that reports it — and the ledger keeps the model's own output unedited underneath.</p>
 */
@Service
@RequiredArgsConstructor
public class RequirementChangeService {

    private final RequirementChangeRepository repository;
    private final RequirementRepository requirements;
    private final ProjectLookupService projectLookup;
    private final ActivityService activityService;
    private final UserService userService;
    private final SprintRiskAdjustmentService adjustments;

    // ------------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public List<RequirementChangeResponse> listForProject(UUID projectId) {
        projectLookup.require(projectId);
        return enrich(repository.findByProjectIdOrderByChangedAtDesc(projectId));
    }

    @Transactional(readOnly = true)
    public RequirementChangeSummary listForSprint(UUID sprintId) {
        List<RequirementChangeResponse> changes = enrich(repository.findBySprintIdOrderByChangedAtDesc(sprintId));

        int increasing = 0;
        int decreasing = 0;
        BigDecimal net = BigDecimal.ZERO;
        for (RequirementChangeResponse change : changes) {
            if (change.riskDelta().signum() < 0) {
                decreasing++;
            } else {
                increasing++;
            }
            net = net.add(change.riskDelta());
        }
        return new RequirementChangeSummary(sprintId, changes.size(), increasing, decreasing, net, changes);
    }

    /**
     * The weights, served so the form can say what a change will cost before it is
     * logged. A client that guessed and disagreed with the server would be worse than
     * one that said nothing.
     */
    public List<RequirementChangeTypeInfo> catalogue() {
        return Arrays.stream(RequirementChangeType.values())
                .map(type -> new RequirementChangeTypeInfo(
                        type,
                        type.isDirectionFixed(),
                        type.riskDelta(ChangeImpactDirection.INCREASE),
                        type.riskDelta(ChangeImpactDirection.DECREASE),
                        type.reason(ChangeImpactDirection.INCREASE),
                        type.reason(ChangeImpactDirection.DECREASE)))
                .toList();
    }

    // ------------------------------------------------------------------ writes

    @Transactional
    public RequirementChangeResponse log(UUID projectId, LogRequirementChangeRequest request) {
        ProjectRef project = projectLookup.requireWritable(projectId);

        RequirementChangeType type = request.changeType();
        ChangeImpactDirection direction = type.effectiveDirection(request.impactDirection());
        BigDecimal delta = type.riskDelta(direction);

        Requirement requirement = null;
        if (request.requirementId() != null) {
            requirement = requirements.findByIdAndDeletedAtIsNull(request.requirementId())
                    .orElseThrow(() -> new ResourceNotFoundException("Requirement", request.requirementId()));
            // The register and the counter are two views of the same fact. Logging a
            // change here without moving the counter would leave the M2 feature blind
            // to exactly the churn somebody took the trouble to write down.
            requirement.registerChange();
            requirements.save(requirement);
        }

        RequirementChange change = RequirementChange.builder()
                .projectId(projectId)
                .sprintId(request.sprintId())
                .requirementId(request.requirementId())
                .storyId(request.storyId())
                .changeType(type)
                .impactDirection(direction)
                .description(request.description())
                .requestedBy(request.requestedBy())
                .requestedByName(trim(request.requestedByName()))
                .riskDelta(delta)
                .build();
        change.setOrganizationId(TenantContext.requireOrganizationId());
        change = repository.save(change);

        // Only a sprint in flight is charged. A change logged with no sprint attached is
        // planning, and charging the next sprint for it would make every sprint start
        // in debt for decisions taken before it existed.
        // Standing, not absorbable. Scope added on the ninth is a fact about this sprint
        // for the rest of its life, and no feature vector carries it — the models read
        // churn off the requirements table, which a story-driven project leaves empty.
        adjustments.recordStanding(request.sprintId(), RiskType.REQUIREMENT_CHANGE,
                delta, type.reason(direction));

        activityService.recordCreated(
                ActivityService.Target.of(EntityType.REQUIREMENT, change.getId(),
                        requirement == null ? project.projectKey() : requirement.getReferenceCode(),
                        projectId),
                "Requirement change logged: %s".formatted(type.reason(direction)));

        return enrich(List.of(change)).get(0);
    }

    @Transactional
    public void delete(UUID changeId) {
        RequirementChange change = repository.findById(changeId)
                .orElseThrow(() -> new ResourceNotFoundException("RequirementChange", changeId));
        projectLookup.requireWritable(change.getProjectId());

        // Withdrawing the record withdraws the charge. Leaving the adjustment behind
        // would make the ledger unauditable: a sprint carrying points for a change
        // nobody can find is exactly the kind of unexplained number that gets the whole
        // risk figure ignored.
        // Standing too, and necessarily: it has to outlive the charge it reverses, or a
        // withdrawal made after an assessment would clear while the original kept paying.
        adjustments.recordStanding(change.getSprintId(), RiskType.REQUIREMENT_CHANGE,
                change.getRiskDelta().negate(),
                "Requirement change withdrawn: %s".formatted(change.getChangeType()));

        repository.delete(change);
    }

    // --------------------------------------------------------------- internals

    /**
     * Fills in the requirement code and the requester's name.
     *
     * <p>Batched over the whole list rather than resolved per row: the register is a
     * table, and a per-row lookup here is the classic way a page that renders fine with
     * five rows takes four seconds with two hundred.</p>
     */
    private List<RequirementChangeResponse> enrich(List<RequirementChange> changes) {
        if (changes.isEmpty()) {
            return List.of();
        }

        List<UUID> requirementIds = changes.stream()
                .map(RequirementChange::getRequirementId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, String> codes = new HashMap<>();
        if (!requirementIds.isEmpty()) {
            requirements.findAllById(requirementIds)
                    .forEach(requirement -> codes.put(requirement.getId(), requirement.getReferenceCode()));
        }

        Map<UUID, UserSummary> requesters = userService.summariesFor(changes.stream()
                .map(RequirementChange::getRequestedBy).filter(Objects::nonNull).distinct().toList());

        List<RequirementChangeResponse> out = new ArrayList<>(changes.size());
        for (RequirementChange change : changes) {
            UserSummary requester = change.getRequestedBy() == null
                    ? null : requesters.get(change.getRequestedBy());
            // The account's name wins over the free-text one when both are present:
            // the typed name goes stale, the account's does not.
            String name = requester != null ? requester.fullName() : change.getRequestedByName();

            out.add(new RequirementChangeResponse(
                    change.getId(), change.getProjectId(), change.getSprintId(),
                    change.getRequirementId(), codes.get(change.getRequirementId()),
                    change.getStoryId(), change.getChangeType(), change.getImpactDirection(),
                    change.getDescription(), change.getRequestedBy(), name,
                    change.getRiskDelta(), change.getChangedAt()));
        }
        return out;
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
