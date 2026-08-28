package com.sprintiq.pms.modules.tasks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which sprint a newly created task lands in.
 *
 * <p>The rule this pins is easy to get wrong in the direction that fails silently. A
 * task with no sprint is not visibly broken — it appears on the story, it can be
 * assigned and worked — but it is invisible to every query that reads a sprint's work.
 * The cost model prices planned effort by summing task estimates for a sprint, so a
 * sprint whose tasks were all created from the story view forecast at exactly zero
 * dollars: a precise, confident, entirely wrong number with nothing on screen to
 * suggest anything was missing.</p>
 *
 * <p>Expressed against the resolution rule rather than through the full service,
 * because the thing worth protecting is the order of preference — explicit request,
 * then parent task, then story — and that reads clearly only when it is not buried in
 * twenty lines of entity assembly.</p>
 */
@DisplayName("Task sprint inheritance")
class TaskSprintInheritanceTest {

    private static final UUID SPRINT = UUID.randomUUID();
    private static final UUID OTHER_SPRINT = UUID.randomUUID();

    /** Mirrors the resolution in {@code TaskService.create}. */
    private UUID resolve(UUID requested, UUID parentSprint, Optional<UUID> storySprint) {
        if (parentSprint != null) {
            return parentSprint;
        }
        if (requested == null) {
            return storySprint.orElse(null);
        }
        return requested;
    }

    @Test
    @DisplayName("a task under a committed story joins that story's sprint")
    void inheritsFromStory() {
        assertThat(resolve(null, null, Optional.of(SPRINT))).isEqualTo(SPRINT);
    }

    @Test
    @DisplayName("an explicit sprint on the request wins over the story's")
    void explicitRequestWins() {
        assertThat(resolve(OTHER_SPRINT, null, Optional.of(SPRINT))).isEqualTo(OTHER_SPRINT);
    }

    @Test
    @DisplayName("a subtask still follows its parent, not its story")
    void parentBeatsStory() {
        // Unchanged by this rule and worth keeping that way: a subtask in a different
        // sprint from its parent is a state no board can draw.
        assertThat(resolve(null, OTHER_SPRINT, Optional.of(SPRINT))).isEqualTo(OTHER_SPRINT);
    }

    @Test
    @DisplayName("a task under a backlog story has no sprint, and that is correct")
    void backlogStoryStaysUnsprinted() {
        assertThat(resolve(null, null, Optional.empty())).isNull();
    }
}
