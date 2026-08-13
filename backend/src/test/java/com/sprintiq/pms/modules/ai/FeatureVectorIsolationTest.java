package com.sprintiq.pms.modules.ai;

import com.sprintiq.pms.modules.ai.application.FeatureVector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The copying behaviour {@code RiskService} depends on to keep two models apart.
 *
 * <p>Both risk types are assessed from one shared vector in a single call. The first
 * version of that code added each model's columns to the shared instance, so the second
 * model was handed the first one's features on top of its own. Nothing threw: CatBoost
 * ignores columns it was not trained on, so the answer stayed plausible. Worse, the
 * feature map is hashed to form the prediction cache key, so a contaminated vector
 * would have been cached under a key no clean request could ever produce, and the wrong
 * answer would have been served for the full six-hour TTL.</p>
 *
 * <p>These pin the two properties that make {@code FeatureVector.create().putAll(other.asMap())}
 * a safe copy, so the shortcut of passing the shared instance directly cannot come back
 * unnoticed.</p>
 */
@DisplayName("FeatureVector copying")
class FeatureVectorIsolationTest {

    @Test
    @DisplayName("a copy can be extended without touching the original")
    void copyDoesNotLeakBackIntoTheSource() {
        FeatureVector shared = FeatureVector.create()
                .putNumber("committed_points", BigDecimal.valueOf(20))
                .putNumber("story_count", 4);

        FeatureVector overBudget = FeatureVector.create().putAll(shared.asMap())
                .put("team_type", "stable")
                .putNumber("planned_hours", BigDecimal.valueOf(150));

        assertThat(overBudget.asMap()).containsKeys("committed_points", "team_type", "planned_hours");
        // The regression: the shared vector must not have grown.
        assertThat(shared.asMap()).containsOnlyKeys("committed_points", "story_count");
    }

    @Test
    @DisplayName("two copies of one source stay independent of each other")
    void siblingsDoNotSeeEachOthersColumns() {
        FeatureVector shared = FeatureVector.create().putNumber("team_size", 6);

        FeatureVector overBudget = FeatureVector.create().putAll(shared.asMap())
                .put("team_type", "high_rework");
        FeatureVector requirementChange = FeatureVector.create().putAll(shared.asMap())
                .put("project_phase", "discovery");

        // Each model must see its own columns and the shared ones — and nothing that
        // belongs to the other model, which is what would silently change its answer.
        assertThat(overBudget.asMap()).containsKeys("team_size", "team_type")
                .doesNotContainKey("project_phase");
        assertThat(requirementChange.asMap()).containsKeys("team_size", "project_phase")
                .doesNotContainKey("team_type");
    }

    @Test
    @DisplayName("asMap hands out a snapshot, not a live view")
    void asMapIsNotAWindowIntoTheVector() {
        FeatureVector vector = FeatureVector.create().putNumber("team_size", 6);
        Map<String, Object> snapshot = vector.asMap();

        vector.put("added_later", "x");

        assertThat(snapshot).doesNotContainKey("added_later");
    }

    @Test
    @DisplayName("putNumber turns a null into zero, which is why nullable inputs use put")
    void putNumberZeroFillsAndPutDoesNot() {
        // Documented as a test because it is a trap rather than a bug: zero-filling is
        // right for the mock, which must never see a gap, and wrong for the real models,
        // where 0% availability or a team of nobody is a meaningful — and catastrophic —
        // value. put() leaves the null so NON_NULL serialisation drops the key and the
        // estimation service falls back to its training median.
        Map<String, Object> filled = FeatureVector.create()
                .putNumber("developer_availability", null).asMap();
        assertThat(filled.get("developer_availability")).isEqualTo(BigDecimal.ZERO);

        Map<String, Object> absent = FeatureVector.create()
                .put("developer_availability", null).asMap();
        assertThat(absent.get("developer_availability")).isNull();
    }
}
