package com.sprintiq.pms.modules.tasks.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.modules.ai.application.port.CollaborationFeatureSource;
import com.sprintiq.pms.modules.tasks.domain.Task;
import com.sprintiq.pms.modules.tasks.domain.TaskRepository;
import com.sprintiq.pms.modules.tasks.domain.TaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns a sprint's task board and its comment threads into the five readings the
 * communication-and-collaboration classifier scores.
 *
 * <p>Every one of them is measured from what the app already records rather than asked
 * of the team. A survey field for "how well is the team communicating?" would be
 * cheaper to build and would measure whoever filled it in.</p>
 *
 * <p>All five are computed in one pass over one window so that they describe the same
 * sprint. Assembling them separately is how the reply time ends up covering the whole
 * sprint while the blocker count covers only today, and nothing about the resulting
 * score would look wrong.</p>
 */
@Component
@RequiredArgsConstructor
public class CollaborationFeatureProvider implements CollaborationFeatureSource {

    /**
     * How long a comment waits before it counts as unanswered.
     *
     * <p>A working day, not an hour. The source system this rule came from used one
     * hour, which is right for a co-located team on a shared clock and wrong for
     * everyone else — it flags every question asked after lunch. A day is long enough
     * that a breach means nobody picked it up rather than that nobody was online.</p>
     */
    private static final Duration UNANSWERED_AFTER = Duration.ofHours(24);

    /**
     * Reply gaps longer than this are dropped from the average rather than clamped.
     *
     * <p>A thread resumed after a fortnight is a new conversation wearing the old one's
     * thread id, and letting one such gap into the mean drags a healthy sprint's
     * average past every threshold on its own. Clamping to the cap instead would still
     * count it as the slowest possible reply, which is the same error made quietly.</p>
     */
    private static final Duration STALE_REPLY_GAP = Duration.ofDays(7);

    private final TaskRepository taskRepository;
    private final CommentThreadReader commentThreads;

    @Override
    @Transactional(readOnly = true)
    public SprintCollaboration collaborationFor(UUID sprintId, Instant windowStart, Instant windowEnd) {
        List<Task> tasks = taskRepository.findBySprintIdAndDeletedAtIsNull(sprintId);
        if (tasks.isEmpty()) {
            return SprintCollaboration.empty();
        }

        List<UUID> taskIds = tasks.stream().map(Task::getId).toList();
        List<CommentThreadReader.ThreadEntry> entries =
                commentThreads.threadEntries(EntityType.TASK, taskIds, windowStart, windowEnd);

        long blocked = tasks.stream()
                // The status and the flag can disagree — a task marked blocked and then
                // dragged to In Progress on the board keeps the flag until someone
                // clears the reason. Either one is a task the team called stuck.
                .filter(task -> task.isBlocked() || task.getStatus() == TaskStatus.BLOCKED)
                .count();
        long reopened = tasks.stream().filter(task -> task.getReopenedCount() > 0).count();

        Map<UUID, List<CommentThreadReader.ThreadEntry>> byTask = groupByTask(entries);

        // The day map is walked three times below; building it once also guarantees
        // the three readings describe the same set of days.
        Set<LocalDate> active = activeDays(tasks, entries);
        int elapsed = elapsedDays(windowStart, windowEnd);
        LocalDate firstDay = windowStart.atZone(ZoneOffset.UTC).toLocalDate();

        return new SprintCollaboration(
                tasks.size(),
                entries.size(),
                commentsPerTask(entries.size(), tasks.size()),
                averageReplyHours(byTask),
                elapsed,
                inactiveDays(active, firstDay, elapsed),
                longestSilenceDays(active, firstDay, elapsed),
                blocked,
                reopened,
                unansweredThreads(byTask, windowEnd),
                contributorCount(tasks, entries));
    }

    // ------------------------------------------------------------------ readings

    private static BigDecimal commentsPerTask(long comments, long tasks) {
        if (tasks <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(comments)
                .divide(BigDecimal.valueOf(tasks), 2, RoundingMode.HALF_UP);
    }

    /**
     * Mean hours between a comment and the next one by somebody else.
     *
     * <p>Consecutive comments by the same person are one person thinking out loud, not a
     * conversation, so they are skipped rather than counted as instant replies — which
     * is what makes a team that talks to itself look responsive.</p>
     *
     * <p>Null, not zero, when no thread in the sprint has two participants. Zero hours
     * is the best possible reading on this scale and "nobody replied to anything" is the
     * worst; reporting one as the other inverts the signal completely.</p>
     */
    private static BigDecimal averageReplyHours(Map<UUID, List<CommentThreadReader.ThreadEntry>> byTask) {
        long totalSeconds = 0;
        int replies = 0;

        for (List<CommentThreadReader.ThreadEntry> thread : byTask.values()) {
            for (int i = 1; i < thread.size(); i++) {
                CommentThreadReader.ThreadEntry previous = thread.get(i - 1);
                CommentThreadReader.ThreadEntry current = thread.get(i);
                if (sameAuthor(previous, current)) {
                    continue;
                }
                Duration gap = Duration.between(previous.postedAt(), current.postedAt());
                if (gap.isNegative() || gap.compareTo(STALE_REPLY_GAP) > 0) {
                    continue;
                }
                totalSeconds += gap.getSeconds();
                replies++;
            }
        }

        if (replies == 0) {
            return null;
        }
        return BigDecimal.valueOf(totalSeconds)
                .divide(BigDecimal.valueOf(3600L * replies), 1, RoundingMode.HALF_UP);
    }

    /**
     * Threads whose last comment is a day old and was never answered by anyone else.
     *
     * <p>Not one of the five the model scores — it is the concrete thing behind an
     * abstract reply-time average, so the UI can name the tasks rather than only report
     * a number nobody can act on.</p>
     */
    private static long unansweredThreads(Map<UUID, List<CommentThreadReader.ThreadEntry>> byTask,
                                          Instant windowEnd) {
        Instant cutoff = windowEnd.minus(UNANSWERED_AFTER);
        long unanswered = 0;

        for (List<CommentThreadReader.ThreadEntry> thread : byTask.values()) {
            if (thread.isEmpty()) {
                continue;
            }
            CommentThreadReader.ThreadEntry last = thread.get(thread.size() - 1);
            if (last.postedAt().isAfter(cutoff)) {
                continue; // Too recent to call unanswered.
            }
            // A single comment nobody responded to counts; so does a thread whose final
            // word came from the person who had been waiting for an answer.
            boolean answered = thread.size() > 1
                    && !sameAuthor(thread.get(thread.size() - 2), last);
            if (!answered) {
                unanswered++;
            }
        }
        return unanswered;
    }

    /**
     * Elapsed days of the sprint with nothing recorded on it.
     *
     * <p>A day counts as active if anything at all happened: a comment, a task started,
     * a task finished, a task touched. Only days that have already passed are counted —
     * a sprint on its third day cannot have eleven silent ones.</p>
     */
    private static int inactiveDays(Set<LocalDate> active, LocalDate firstDay, int elapsed) {
        int inactive = 0;
        for (int day = 0; day < elapsed; day++) {
            if (!active.contains(firstDay.plusDays(day))) {
                inactive++;
            }
        }
        return inactive;
    }

    /**
     * The longest unbroken run of silent days.
     *
     * <p>Four scattered quiet days across a fortnight is a team with a rhythm. Four
     * consecutive ones is a team that stopped. They produce the same
     * {@code inactiveDays}, which is why this is reported alongside it.</p>
     */
    private static int longestSilenceDays(Set<LocalDate> active, LocalDate firstDay, int elapsed) {
        int longest = 0;
        int run = 0;
        for (int day = 0; day < elapsed; day++) {
            if (active.contains(firstDay.plusDays(day))) {
                run = 0;
            } else {
                run++;
                longest = Math.max(longest, run);
            }
        }
        return longest;
    }

    /** Distinct people who wrote or were assigned something this sprint. */
    private static long contributorCount(List<Task> tasks,
                                         List<CommentThreadReader.ThreadEntry> entries) {
        Set<UUID> people = new HashSet<>();
        for (Task task : tasks) {
            if (task.getAssigneeId() != null) {
                people.add(task.getAssigneeId());
            }
        }
        for (CommentThreadReader.ThreadEntry entry : entries) {
            if (entry.authorId() != null) {
                people.add(entry.authorId());
            }
        }
        return people.size();
    }

    // ------------------------------------------------------------------ internals

    private static Map<UUID, List<CommentThreadReader.ThreadEntry>> groupByTask(
            List<CommentThreadReader.ThreadEntry> entries) {
        // The repository orders by item then time, so appending preserves chronology
        // within each thread — which every reading below depends on.
        Map<UUID, List<CommentThreadReader.ThreadEntry>> byTask = new HashMap<>();
        for (CommentThreadReader.ThreadEntry entry : entries) {
            byTask.computeIfAbsent(entry.itemId(), key -> new ArrayList<>()).add(entry);
        }
        return byTask;
    }

    private static Set<LocalDate> activeDays(List<Task> tasks,
                                             List<CommentThreadReader.ThreadEntry> entries) {
        Set<LocalDate> days = new HashSet<>();
        for (CommentThreadReader.ThreadEntry entry : entries) {
            days.add(entry.postedAt().atZone(ZoneOffset.UTC).toLocalDate());
        }
        for (Task task : tasks) {
            addDay(days, task.getStartedAt());
            addDay(days, task.getCompletedAt());
            addDay(days, task.getUpdatedAt());
        }
        return days;
    }

    private static void addDay(Set<LocalDate> days, Instant moment) {
        if (moment != null) {
            days.add(moment.atZone(ZoneOffset.UTC).toLocalDate());
        }
    }

    private static int elapsedDays(Instant windowStart, Instant windowEnd) {
        if (!windowEnd.isAfter(windowStart)) {
            return 0;
        }
        LocalDate start = windowStart.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate end = windowEnd.atZone(ZoneOffset.UTC).toLocalDate();
        // Today is excluded: it is not over, so calling it silent is premature.
        return (int) java.time.temporal.ChronoUnit.DAYS.between(start, end);
    }

    private static boolean sameAuthor(CommentThreadReader.ThreadEntry a,
                                      CommentThreadReader.ThreadEntry b) {
        // Two comments with no recorded author (imported, or written by an integration)
        // are treated as the same voice. Counting them as a reply to each other would
        // manufacture responsiveness out of missing data.
        return a.authorId() == null || b.authorId() == null || a.authorId().equals(b.authorId());
    }

    /**
     * Each task's timing facts, for the between-assessment watch.
     *
     * <p>Comments are fetched once for the whole sprint and grouped, rather than
     * queried per task. A sprint with forty tasks would otherwise issue forty queries
     * on a schedule that runs every few minutes.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public List<WatchedTask> watchedTasks(UUID sprintId) {
        List<Task> tasks = taskRepository.findBySprintIdAndDeletedAtIsNull(sprintId);
        if (tasks.isEmpty()) {
            return List.of();
        }

        List<UUID> ids = tasks.stream().map(Task::getId).toList();
        // Unbounded window: the watch rules ask "has anyone said anything at all",
        // which a window would answer wrongly for a comment made just before it opened.
        Map<UUID, List<CommentThreadReader.ThreadEntry>> byTask = commentThreads
                .threadEntries(EntityType.TASK, ids, Instant.EPOCH, Instant.now())
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        CommentThreadReader.ThreadEntry::itemId));

        List<WatchedTask> watched = new ArrayList<>(tasks.size());
        for (Task task : tasks) {
            List<CommentThreadReader.ThreadEntry> comments =
                    byTask.getOrDefault(task.getId(), List.of());
            Instant lastComment = comments.isEmpty()
                    ? null
                    : comments.get(comments.size() - 1).postedAt();
            watched.add(new WatchedTask(
                    task.getId(),
                    task.getTitle(),
                    task.getStatus() == null ? TaskStatus.TODO.name() : task.getStatus().name(),
                    task.getEstimateMinutes(),
                    task.getCreatedAt(),
                    comments.size(),
                    lastComment));
        }
        return watched;
    }
}
