package com.sprintiq.pms.modules.copilot.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.ApplicationException;
import com.sprintiq.pms.common.error.ErrorCode;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.ai.application.AiFeedbackService;
import com.sprintiq.pms.modules.ai.domain.AiModelCode;
import com.sprintiq.pms.modules.ai.domain.FeedbackAction;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.PredictionEnvelope;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.SubmitFeedbackRequest;
import com.sprintiq.pms.modules.backlog.application.StoryService;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.StoryResponse;
import com.sprintiq.pms.modules.copilot.application.tools.InvalidToolArgumentsException;
import com.sprintiq.pms.modules.copilot.application.tools.ToolContext;
import com.sprintiq.pms.modules.copilot.domain.CopilotProposal;
import com.sprintiq.pms.modules.copilot.domain.CopilotProposalRepository;
import com.sprintiq.pms.modules.copilot.domain.ProposalStatus;
import com.sprintiq.pms.modules.copilot.domain.ProposalType;
import com.sprintiq.pms.modules.copilot.web.dto.CopilotDtos.ProposalResponse;
import com.sprintiq.pms.modules.sprints.application.SprintService;
import com.sprintiq.pms.modules.sprints.domain.SprintStatus;
import com.sprintiq.pms.modules.sprints.web.dto.SprintDtos.AddSprintItemsRequest;
import com.sprintiq.pms.modules.sprints.web.dto.SprintDtos.SprintResponse;
import com.sprintiq.pms.modules.tasks.application.TaskService;
import com.sprintiq.pms.modules.tasks.domain.AssignmentSource;
import com.sprintiq.pms.modules.tasks.web.dto.TaskDtos.AssignTaskRequest;
import com.sprintiq.pms.modules.tasks.web.dto.TaskDtos.TaskResponse;
import com.sprintiq.pms.modules.users.application.UserService;
import com.sprintiq.pms.modules.users.application.UserSummary;
import com.sprintiq.pms.security.PermissionService;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Changes the Copilot proposes, and the only path by which one is carried out.
 *
 * <p>Creating a proposal validates it against live data and writes the summary from
 * that data — never from the model's words — so the card a person confirms says
 * exactly what the confirm will do. Confirming re-checks ownership, expiry and
 * permission, then calls the same service a person clicking through the UI would.</p>
 */
@Slf4j
@Service
public class ProposalService {

    /** Long enough to come back after lunch; short enough that the data it read is still roughly true. */
    static final Duration TTL = Duration.ofHours(24);

    /** One proposal should be reviewable at a glance. */
    static final int MAX_STORIES_PER_MOVE = 20;

    private final CopilotProposalRepository proposals;
    private final AiFeedbackService feedback;
    private final StoryService stories;
    private final TaskService tasks;
    private final SprintService sprints;
    private final UserService users;
    private final PermissionService perm;
    private final TransactionTemplate tx;

    public ProposalService(CopilotProposalRepository proposals, AiFeedbackService feedback,
                           StoryService stories, TaskService tasks, SprintService sprints,
                           UserService users, PermissionService perm,
                           PlatformTransactionManager transactionManager) {
        this.proposals = proposals;
        this.feedback = feedback;
        this.stories = stories;
        this.tasks = tasks;
        this.sprints = sprints;
        this.users = users;
        this.perm = perm;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** The permission a proposal of this type needs — to be offered, created and confirmed. */
    public static String permissionFor(ProposalType type) {
        return switch (type) {
            case ACCEPT_ESTIMATE -> "backlog:manage";
            case ASSIGN_TASK -> "task:assign";
            case MOVE_TO_SPRINT -> "sprint:manage";
        };
    }

    // ------------------------------------------------------------------ propose

    public ProposalResponse proposeAcceptEstimate(ToolContext ctx, UUID predictionId, String reason) {
        perm.require(permissionFor(ProposalType.ACCEPT_ESTIMATE));

        PredictionEnvelope prediction = feedback.detail(predictionId).prediction();
        if (prediction.modelCode() != AiModelCode.M1_STORY_POINTS
                || prediction.entityType() != EntityType.STORY || prediction.value() == null) {
            throw new InvalidToolArgumentsException("predictionId must be a story-point estimate "
                    + "from estimate_story_points.");
        }
        StoryResponse story = stories.get(prediction.entityId());
        BigDecimal points = prediction.value().stripTrailingZeros();

        String summary = "Set %s “%s” to %s points (M1 suggestion; currently %s)".formatted(
                story.itemKey(), story.title(), points.toPlainString(),
                story.storyPoints() == null ? "unestimated" : story.storyPoints().stripTrailingZeros().toPlainString());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("predictionId", predictionId.toString());
        payload.put("storyId", story.id().toString());
        payload.put("points", points.toPlainString());
        return create(ctx, ProposalType.ACCEPT_ESTIMATE, payload, summary, reason);
    }

    public ProposalResponse proposeAssignTask(ToolContext ctx, UUID taskId, UUID assigneeId,
                                              UUID predictionId, String reason) {
        perm.require(permissionFor(ProposalType.ASSIGN_TASK));

        TaskResponse task = tasks.get(taskId);
        users.requireActiveMember(assigneeId);
        UserSummary person = users.summariesFor(List.of(assigneeId)).get(assigneeId);
        String name = person == null ? assigneeId.toString() : person.fullName();

        String summary = "Assign %s “%s” to %s".formatted(task.itemKey(), task.title(), name);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId.toString());
        payload.put("assigneeId", assigneeId.toString());
        payload.put("assigneeName", name);
        if (predictionId != null) payload.put("predictionId", predictionId.toString());
        return create(ctx, ProposalType.ASSIGN_TASK, payload, summary, reason);
    }

    public ProposalResponse proposeMoveToSprint(ToolContext ctx, List<UUID> storyIds, UUID sprintId,
                                                String reason) {
        perm.require(permissionFor(ProposalType.MOVE_TO_SPRINT));
        if (storyIds.size() > MAX_STORIES_PER_MOVE) {
            throw new InvalidToolArgumentsException("At most " + MAX_STORIES_PER_MOVE
                    + " stories per proposal.");
        }

        SprintResponse sprint = sprints.get(sprintId);
        if (sprint.status() != SprintStatus.PLANNED && sprint.status() != SprintStatus.ACTIVE) {
            throw new InvalidToolArgumentsException(sprint.name() + " is " + sprint.status()
                    + "; stories can only be added to a planned or active sprint.");
        }

        List<String> keys = new ArrayList<>();
        for (UUID storyId : storyIds) {
            StoryResponse story = stories.get(storyId);
            if (!sprint.projectId().equals(story.projectId())) {
                throw new InvalidToolArgumentsException(story.itemKey()
                        + " belongs to a different project from " + sprint.name() + ".");
            }
            keys.add(story.itemKey());
        }

        String summary = "Add %d %s to %s: %s".formatted(keys.size(),
                keys.size() == 1 ? "story" : "stories", sprint.name(), String.join(", ", keys));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sprintId", sprintId.toString());
        payload.put("storyIds", storyIds.stream().map(UUID::toString).toList());
        return create(ctx, ProposalType.MOVE_TO_SPRINT, payload, summary, reason);
    }

    private ProposalResponse create(ToolContext ctx, ProposalType type, Map<String, Object> payload,
                                    String summary, String reason) {
        if (reason != null && !reason.isBlank()) {
            payload.put("reason", reason.length() > 300 ? reason.substring(0, 300) : reason);
        }
        CopilotProposal proposal = proposals.save(CopilotProposal.builder()
                .conversationId(ctx.conversationId())
                .userId(ctx.userId())
                .type(type)
                .payload(payload)
                .summary(summary.length() > 500 ? summary.substring(0, 497) + "…" : summary)
                .expiresAt(Instant.now().plus(TTL))
                .build());
        ctx.recordProposal(proposal.getId());
        return toResponse(proposal, Instant.now());
    }

    // ------------------------------------------------------------------ resolve

    /** What the confirm transaction decided, carried out of it so the outcome survives commit. */
    private record Decision(CopilotProposal proposal, boolean expired) {}

    /** The action itself refused. Distinct from a precondition failing, which is the caller's error. */
    private static final class ActionFailed extends RuntimeException {
        ActionFailed(RuntimeException cause) {
            super(cause);
        }
    }

    public ProposalResponse confirm(UUID proposalId) {
        UUID userId = TenantContext.requireUserId();
        Decision decision;
        try {
            decision = tx.execute(status -> {
                CopilotProposal p = proposals.findForUpdate(proposalId, userId)
                        .orElseThrow(() -> ResourceNotFoundException.of("Proposal", proposalId));
                Instant now = Instant.now();
                if (p.isExpiredAt(now)) {
                    p.resolve(ProposalStatus.EXPIRED, null, "Expired before it was confirmed.", now);
                    proposals.save(p);
                    return new Decision(p, true);
                }
                if (p.getStatus() != ProposalStatus.PENDING) {
                    throw new ApplicationException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                            "This proposal is already " + p.getStatus().name().toLowerCase() + ".");
                }
                perm.require(permissionFor(p.getType()));

                String message;
                try {
                    message = carryOut(p);
                } catch (RuntimeException ex) {
                    throw new ActionFailed(ex);
                }
                p.resolve(ProposalStatus.CONFIRMED, userId, message, now);
                proposals.save(p);
                return new Decision(p, false);
            });
        } catch (ActionFailed failed) {
            // The action's transaction has rolled back; recording why is a new one.
            return recordFailure(proposalId, userId, failed.getCause());
        }

        if (decision.expired()) {
            throw new ApplicationException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "This proposal expired. Ask the Copilot again so it can re-read the current data.");
        }
        return toResponse(decision.proposal(), Instant.now());
    }

    private ProposalResponse recordFailure(UUID proposalId, UUID userId, Throwable cause) {
        String message = cause instanceof ApplicationException
                ? cause.getMessage() : "The change could not be applied.";
        if (!(cause instanceof ApplicationException)) {
            log.warn("Copilot proposal {} failed", proposalId, cause);
        }
        CopilotProposal p = tx.execute(status -> proposals.findById(proposalId)
                .map(found -> {
                    if (found.getStatus() == ProposalStatus.PENDING) {
                        found.resolve(ProposalStatus.FAILED, userId, message, Instant.now());
                        proposals.save(found);
                    }
                    return found;
                })
                .orElseThrow(() -> ResourceNotFoundException.of("Proposal", proposalId)));
        return toResponse(p, Instant.now());
    }

    @Transactional
    public ProposalResponse reject(UUID proposalId) {
        UUID userId = TenantContext.requireUserId();
        CopilotProposal p = proposals.findForUpdate(proposalId, userId)
                .orElseThrow(() -> ResourceNotFoundException.of("Proposal", proposalId));
        Instant now = Instant.now();
        if (p.effectiveStatus(now) != ProposalStatus.PENDING) {
            throw new ApplicationException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "This proposal is already " + p.effectiveStatus(now).name().toLowerCase() + ".");
        }
        p.resolve(ProposalStatus.REJECTED, userId, null, now);
        proposals.save(p);
        return toResponse(p, now);
    }

    /** Runs the change through the owning module's service. Returns a line for the record. */
    private String carryOut(CopilotProposal p) {
        Map<String, Object> payload = p.getPayload();
        return switch (p.getType()) {
            case ACCEPT_ESTIMATE -> {
                BigDecimal points = new BigDecimal(String.valueOf(payload.get("points")));
                feedback.submit(uuid(payload, "predictionId"), new SubmitFeedbackRequest(
                        FeedbackAction.ACCEPTED, points, "Accepted through the Copilot", true));
                yield "Estimate set to " + points.toPlainString() + " points.";
            }
            case ASSIGN_TASK -> {
                UUID predictionId = payload.containsKey("predictionId") ? uuid(payload, "predictionId") : null;
                tasks.assign(uuid(payload, "taskId"), new AssignTaskRequest(uuid(payload, "assigneeId"),
                        predictionId == null ? AssignmentSource.MANUAL : AssignmentSource.AI_ACCEPTED,
                        predictionId));
                yield "Assigned to " + payload.getOrDefault("assigneeName", "the selected person") + ".";
            }
            case MOVE_TO_SPRINT -> {
                @SuppressWarnings("unchecked")
                List<Object> raw = (List<Object>) payload.get("storyIds");
                List<UUID> storyIds = raw.stream().map(o -> UUID.fromString(String.valueOf(o))).toList();
                sprints.addItems(uuid(payload, "sprintId"), new AddSprintItemsRequest(storyIds));
                yield "Added " + storyIds.size() + (storyIds.size() == 1 ? " story." : " stories.");
            }
        };
    }

    private static UUID uuid(Map<String, Object> payload, String key) {
        return UUID.fromString(String.valueOf(payload.get(key)));
    }

    // ------------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public List<ProposalResponse> forConversation(UUID conversationId) {
        Instant now = Instant.now();
        return proposals.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .map(p -> toResponse(p, now)).toList();
    }

    /** Links a turn's proposals to the answer that offered them, once that answer is saved. */
    @Transactional
    public void attachToMessage(List<UUID> proposalIds, UUID messageId) {
        if (proposalIds.isEmpty()) return;
        proposals.findAllById(proposalIds).forEach(p -> {
            p.setMessageId(messageId);
            proposals.save(p);
        });
    }

    static ProposalResponse toResponse(CopilotProposal p, Instant now) {
        Object reason = p.getPayload().get("reason");
        return new ProposalResponse(p.getId(), p.getConversationId(), p.getMessageId(), p.getType(),
                p.getSummary(), reason == null ? null : reason.toString(), p.effectiveStatus(now),
                p.getExpiresAt(), p.getResolvedAt(), p.getResultMessage(), p.getPayload());
    }
}
