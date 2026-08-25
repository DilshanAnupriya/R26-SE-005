package com.sprintiq.pms.modules.tasks;

import com.sprintiq.pms.modules.ai.application.port.EffortFeatureSource.SprintSlippage;
import com.sprintiq.pms.modules.tasks.application.TaskFeatureProvider;
import com.sprintiq.pms.modules.tasks.domain.Task;
import com.sprintiq.pms.modules.tasks.domain.TaskRepository;
import com.sprintiq.pms.modules.tasks.domain.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The count that has to reach the over-budget model for Assess to be non-destructive.
 *
 * <p>Companion to {@code BlockedTaskSurvivesAssessTest}, which checks the far end: that
 * the model moves when {@code blocked_count} rises. This checks the near end — that the
 * figure rises at all when somebody drags a card into the board's Blocked column.</p>
 *
 * <p>That was the actual defect. The sprint's blocked figure came off the backlog
 * snapshot, which counts blocked <em>stories</em>; the board blocks <em>tasks</em>. Both
 * halves have to hold, and each passes on its own while the feature stays broken, which
 * is why they are two tests rather than one.</p>
 */
@DisplayName("Blocked tasks in the sprint slippage")
class SprintSlippageBlockedCountTest {

    private static final UUID SPRINT = UUID.randomUUID();

    @Test
    @DisplayName("counts a task sitting in the Blocked column")
    void countsBlockedTasks() {
        SprintSlippage slippage = slippageOver(
                task(TaskStatus.BLOCKED, 480),
                task(TaskStatus.IN_PROGRESS, 480),
                task(TaskStatus.TODO, 480));

        assertThat(slippage.blockedTasks()).isEqualTo(1);
        assertThat(slippage.taskCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("counts an unsized blocked task, which the estimate guard would drop")
    void countsUnestimatedBlockedTasks() {
        // Everything else in this loop is skipped when a task has no estimate. A task
        // nobody sized is stuck exactly as much as a sized one, and a count that depended
        // on whether somebody had estimated it would move when a card was estimated.
        SprintSlippage slippage = slippageOver(task(TaskStatus.BLOCKED, null));

        assertThat(slippage.blockedTasks()).isEqualTo(1);
        assertThat(slippage.estimatedTaskCount()).isZero();
    }

    @Test
    @DisplayName("a sprint with nothing stuck reports nothing stuck")
    void unblockedSprintCountsZero() {
        assertThat(slippageOver(task(TaskStatus.IN_PROGRESS, 480), task(TaskStatus.DONE, 480))
                .blockedTasks()).isZero();
    }

    @Test
    @DisplayName("an empty sprint is not an error")
    void emptySprint() {
        assertThat(slippageOver().blockedTasks()).isZero();
        assertThat(slippageOver().taskCount()).isZero();
    }

    // ------------------------------------------------------------------- fixtures

    private static Task task(TaskStatus status, Integer estimateMinutes) {
        Task task = Task.builder()
                .sprintId(SPRINT)
                .itemKey("MBA-1")
                .title("Wire the payment callback")
                .status(status)
                .estimateMinutes(estimateMinutes)
                .build();
        // The board keeps the flag and the status coherent; the builder does not, so the
        // fixture says both rather than relying on whichever one the count happens to read.
        task.setBlocked(status == TaskStatus.BLOCKED);
        return task;
    }

    private static SprintSlippage slippageOver(Task... tasks) {
        return new TaskFeatureProvider(repositoryOf(List.of(tasks)), null, null)
                .sprintSlippage(SPRINT);
    }

    /**
     * The repository, as the one method this path calls.
     *
     * <p>A proxy rather than a class, because implementing {@code JpaRepository} by hand
     * to stub a single finder would be thirty methods of noise around one line.</p>
     */
    private static TaskRepository repositoryOf(List<Task> tasks) {
        return (TaskRepository) Proxy.newProxyInstance(
                TaskRepository.class.getClassLoader(),
                new Class<?>[]{TaskRepository.class},
                (proxy, method, args) -> {
                    if (!"findBySprintIdAndDeletedAtIsNull".equals(method.getName())) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    return tasks;
                });
    }
}
