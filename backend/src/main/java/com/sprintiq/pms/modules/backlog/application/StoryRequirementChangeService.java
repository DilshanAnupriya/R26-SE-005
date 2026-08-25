package com.sprintiq.pms.modules.backlog.application;

import com.sprintiq.pms.common.error.BusinessRuleException;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.backlog.domain.Story;
import com.sprintiq.pms.modules.backlog.domain.StoryRepository;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.ApplyStoryChangeRequest;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.StoryChangeResponse;
import com.sprintiq.pms.modules.projects.application.RequirementChangeService;
import com.sprintiq.pms.modules.projects.domain.ChangeImpactDirection;
import com.sprintiq.pms.modules.projects.domain.RequirementChangeImpact;
import com.sprintiq.pms.modules.projects.domain.RequirementChangeType;
import com.sprintiq.pms.modules.projects.web.dto.RequirementChangeDtos.LogRequirementChangeRequest;
import com.sprintiq.pms.modules.projects.web.dto.RequirementChangeDtos.RequirementChangeResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Editing a story and recording why, as one action.
 *
 * <p>The register could already log that a requirement moved, and the backlog could
 * already edit a story. Doing them separately meant the two drifted: somebody would
 * change a priority on the board and never log it, or log a change and never make it, and
 * the risk score would be reading a register that described a story nobody had
 * touched.</p>
 *
 * <p>Here they are one transaction. The story is edited, the register records the edit,
 * and the sprint is charged — and because the edit is what the register is built from,
 * the direction no longer has to be asked for. It is read out of the two versions by
 * {@link RequirementChangeImpact}, which is what stops the same change costing +5 or −3
 * depending on which option the author happened to pick.</p>
 *
 * <p>Lives in the backlog rather than beside the register because it owns a
 * {@link Story}, and the layering runs backlog → projects. The register is called through
 * its published service, exactly as any other caller would.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoryRequirementChangeService {

    private final StoryRepository storyRepository;
    private final StoryService storyService;
    private final RequirementChangeService requirementChanges;

    /**
     * Applies the edit, logs it, and charges the sprint.
     *
     * <p>Refuses an edit that changes nothing. A form submitted unaltered is not a
     * requirement change, and charging the sprint for one would let anybody move the risk
     * score by opening a dialog and pressing Save.</p>
     */
    @Transactional
    public StoryChangeResponse apply(UUID storyId, ApplyStoryChangeRequest request) {
        Story story = storyRepository.findByIdAndDeletedAtIsNull(storyId)
                .orElseThrow(() -> new ResourceNotFoundException("Story", storyId));

        RequirementChangeType type = request.changeType();
        RequirementChangeImpact.Snapshot before = snapshot(story);
        RequirementChangeImpact.Snapshot after = proposed(story, request);

        ChangeImpactDirection direction =
                RequirementChangeImpact.infer(type, before, after, request.impactDirection());
        if (direction == null) {
            throw new BusinessRuleException(
                    "Nothing changed, so there is no requirement change to log. Edit the "
                    + "field this change type describes, or pick a different type.");
        }
        requireCoherentScope(type, before, after);

        applyTo(story, type, after);
        storyRepository.save(story);

        // Only a sprint in flight is charged, and only when the author says this belongs
        // to it — the register's own rule, not a new one. A story sitting in the backlog
        // has no sprint to bill.
        UUID sprintId = request.attachToSprint() ? story.getSprintId() : null;

        RequirementChangeResponse change = requirementChanges.log(story.getProjectId(),
                new LogRequirementChangeRequest(type, direction, sprintId,
                        story.getRequirementId(), storyId, request.description(),
                        null, request.requestedByName()));

        log.info("Story {} changed ({} {}) — requirement risk {}",
                story.getItemKey(), type, direction, change.riskDelta());

        return new StoryChangeResponse(storyService.toResponse(story), change);
    }

    // ---------------------------------------------------------------- internals

    /**
     * Scope changes have to move the estimate, and move it the way they claim.
     *
     * <p>The two scope types carry their sign in their name, so {@code infer} never
     * returns null for them and the no-op guard above cannot catch an untouched form.
     * Worse, nothing otherwise stops "scope removed" being logged against a story whose
     * points went up — which would credit the sprint 5 points for adding work.</p>
     */
    private void requireCoherentScope(RequirementChangeType type,
                                      RequirementChangeImpact.Snapshot before,
                                      RequirementChangeImpact.Snapshot after) {
        if (type != RequirementChangeType.SCOPE_ADDED && type != RequirementChangeType.SCOPE_REMOVED) {
            return;
        }
        BigDecimal was = before.storyPoints() == null ? BigDecimal.ZERO : before.storyPoints();
        BigDecimal now = after.storyPoints() == null ? BigDecimal.ZERO : after.storyPoints();
        int moved = now.compareTo(was);

        if (moved == 0) {
            throw new BusinessRuleException(
                    "Scope changes are recorded against the estimate. Set the story's new "
                    + "size, or log this as a description update instead.");
        }
        if (type == RequirementChangeType.SCOPE_ADDED && moved < 0) {
            throw new BusinessRuleException(
                    "Scope added, but the estimate went down. Log this as scope removed.");
        }
        if (type == RequirementChangeType.SCOPE_REMOVED && moved > 0) {
            throw new BusinessRuleException(
                    "Scope removed, but the estimate went up. Log this as scope added.");
        }
    }

    private RequirementChangeImpact.Snapshot snapshot(Story story) {
        return new RequirementChangeImpact.Snapshot(story.getPriority(), story.getDescription(),
                story.getAcceptanceCriteria(), story.getStoryPoints());
    }

    /**
     * The story as it would be, with only the field this change type describes moved.
     *
     * <p>Everything else is carried over from the current story rather than taken from
     * the request. The form only edits one field, and letting an unrelated one through
     * would mean a priority change could silently rewrite a description while the
     * register recorded a priority change.</p>
     */
    private RequirementChangeImpact.Snapshot proposed(Story story, ApplyStoryChangeRequest request) {
        return switch (request.changeType()) {
            case PRIORITY_CHANGED -> new RequirementChangeImpact.Snapshot(
                    request.newPriority() == null ? story.getPriority() : request.newPriority(),
                    story.getDescription(), story.getAcceptanceCriteria(), story.getStoryPoints());
            case DESCRIPTION_UPDATED -> new RequirementChangeImpact.Snapshot(
                    story.getPriority(), request.newDescription(),
                    story.getAcceptanceCriteria(), story.getStoryPoints());
            case ACCEPTANCE_CRITERIA_CHANGED -> new RequirementChangeImpact.Snapshot(
                    story.getPriority(), story.getDescription(),
                    request.newAcceptanceCriteria(), story.getStoryPoints());
            case SCOPE_ADDED, SCOPE_REMOVED -> new RequirementChangeImpact.Snapshot(
                    story.getPriority(), story.getDescription(), story.getAcceptanceCriteria(),
                    request.newStoryPoints() == null ? story.getStoryPoints() : request.newStoryPoints());
            // OTHER edits nothing on the story; it is a note on the record.
            case OTHER -> snapshot(story);
        };
    }

    /** Writes the one field this change type owns. */
    private void applyTo(Story story, RequirementChangeType type,
                         RequirementChangeImpact.Snapshot after) {
        switch (type) {
            case PRIORITY_CHANGED -> story.setPriority(after.priority());
            case DESCRIPTION_UPDATED -> story.setDescription(after.description());
            case ACCEPTANCE_CRITERIA_CHANGED -> story.setAcceptanceCriteria(after.acceptanceCriteria());
            case SCOPE_ADDED, SCOPE_REMOVED -> story.setStoryPoints(nonNegative(after.storyPoints()));
            case OTHER -> { /* nothing on the story moves */ }
        }
    }

    /** A story cannot be worth less than nothing, however much scope comes out of it. */
    private BigDecimal nonNegative(BigDecimal points) {
        return points == null || points.signum() >= 0 ? points : BigDecimal.ZERO;
    }
}
