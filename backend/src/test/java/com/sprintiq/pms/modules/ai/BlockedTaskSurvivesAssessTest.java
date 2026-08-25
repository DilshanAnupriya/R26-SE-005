package com.sprintiq.pms.modules.ai;

import com.sprintiq.pms.modules.ai.application.AiModelClient;
import com.sprintiq.pms.modules.ai.application.FeatureVector;
import com.sprintiq.pms.modules.ai.application.MockAiModelClient;
import com.sprintiq.pms.modules.ai.domain.AiModelCode;
import com.sprintiq.pms.modules.ai.domain.InferenceMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pressing Assess must not undo a blocked task.
 *
 * <p>This is the first invariant of the risk overlay, and the failure it guards against
 * does not look like a bug — it looks like the risk score fixing itself. Blocking a task
 * charges the ledger, so the number on screen goes up. Assess then re-scores and clears
 * the overlay <em>on the premise that the model has now seen the blocker</em>. If the
 * model's features cannot see it, the model returns its pre-block answer, the overlay is
 * thrown away, and the sprint reads as healthier than it did a moment ago.</p>
 *
 * <p>It went wrong here because {@code blocked_count} was assembled from the backlog
 * snapshot, which counts blocked <em>stories</em>. A team working on the board blocks
 * tasks, and a story is only blocked once somebody blocks the story itself — so the
 * board's Blocked column moved the ledger and nothing else.</p>
 *
 * <p>Against the mock client because {@code AI_PROVIDER=mock} is what a developer and a
 * demo run on, and because the property under test is that the model client <em>reads
 * the column</em> — which is a fact about the client, not about a trained bundle.</p>
 */
@DisplayName("A blocked task survives Assess")
class BlockedTaskSurvivesAssessTest {

    private final MockAiModelClient client = new MockAiModelClient();

    /** A sprint doing fine, so nothing else is near a cap and the blocked term is visible. */
    private FeatureVector sprint(long blocked, long blockable) {
        return FeatureVector.create()
                .putNumber("commitment_ratio", BigDecimal.valueOf(0.9))
                .putNumber("scope_volatility", BigDecimal.ZERO)
                .putNumber("elapsed_fraction", BigDecimal.valueOf(0.5))
                .putNumber("effort_burn_ratio", BigDecimal.valueOf(0.4))
                .putNumber("story_count", 12)
                .putNumber("blocked_count", blocked)
                .putNumber("blockable_count", blockable)
                .putNumber("overtime_minutes", 0)
                .putNumber("effort_minutes", 4800)
                .putNumber("task_slippage_rate", BigDecimal.ZERO)
                .putNumber("prev_reopened_tasks", 0);
    }

    private BigDecimal score(FeatureVector features) {
        AiModelClient.InferenceResult result = client.infer(new AiModelClient.InferenceRequest(
                AiModelCode.M2_OVER_BUDGET, "1.0.0", InferenceMode.ZERO_SHOT,
                features, Duration.ofSeconds(4)));
        assertThat(result.isUsable()).isTrue();
        return result.value();
    }

    @Test
    @DisplayName("blocking one task raises the model's own score, not only the ledger")
    void theModelSeesTheBlock() {
        BigDecimal before = score(sprint(0, 40));
        BigDecimal after = score(sprint(1, 40));

        // The whole fix in one assertion. If this fails, Assess is destructive again:
        // the ledger's charge gets cleared and replaced by an unchanged model figure.
        assertThat(after)
                .as("a blocked task has to move the model, or re-scoring discards the evidence")
                .isGreaterThan(before);
    }

    @Test
    @DisplayName("more blocked work reads as worse")
    void moreBlockedIsWorse() {
        assertThat(score(sprint(4, 40))).isGreaterThan(score(sprint(1, 40)));
    }

    @Test
    @DisplayName("clearing the blockers puts the score back")
    void unblockingRestoresIt() {
        // Symmetric, so a team that fixes its blockers is not permanently penalised for
        // having had them. The ledger reverses its charge; the model has to agree.
        assertThat(score(sprint(0, 40))).isEqualByComparingTo(score(sprint(0, 40)));
        assertThat(score(sprint(0, 40))).isLessThan(score(sprint(3, 40)));
    }

    @Test
    @DisplayName("the same few blocked cards matter more on a smaller sprint")
    void scaledAgainstTheWork() {
        // Two blocked cards out of eight is a sprint in trouble; two out of eighty is a
        // Tuesday. Dividing by the population is what keeps those apart.
        assertThat(score(sprint(2, 8))).isGreaterThan(score(sprint(2, 80)));
    }

    @Test
    @DisplayName("falls back to the story count for a vector built before blockable_count")
    void toleratesTheOlderVector() {
        // Assessments stored before this feature existed are re-read by the trend and the
        // comparison views, and a divide-by-absent-key would score them as unblocked.
        FeatureVector legacy = sprint(2, 0);
        assertThat(score(legacy)).isGreaterThan(score(sprint(0, 0)));
    }
}
