package com.sprintiq.pms.seed;

import com.sprintiq.pms.modules.projects.domain.Project;
import com.sprintiq.pms.modules.projects.domain.ProjectMemberRepository;
import com.sprintiq.pms.modules.projects.domain.ProjectRepository;
import com.sprintiq.pms.modules.sprints.domain.Sprint;
import com.sprintiq.pms.modules.sprints.domain.SprintRepository;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Gives sprint history to projects that already exist.
 *
 * <p>{@link DemoDataSeeder} builds a whole organization from nothing and refuses to run
 * against a database that already has one. That is right for a fresh checkout and no
 * help at all for the common case: a project somebody has been using for weeks, with
 * two or three closed sprints, whose velocity chart is three bars and whose burndown is
 * a dot. This backfills the sprints behind it.</p>
 *
 * <h2>It writes to real data, so it is hard to start by accident</h2>
 *
 * <p>Off unless a property names the projects explicitly — there is no "all projects"
 * setting, because the one thing this must never do is surprise somebody by inventing
 * delivery history across a database they were using for something else. Run it as:</p>
 *
 * <pre>
 * ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev \
 *   -Dspring-boot.run.arguments=--app.backfill.projects="Nimbus Payments,Payroll SaaS"
 * </pre>
 *
 * <p>It is additive: it creates sprints and stories and never edits or deletes anything
 * that was there before, with the single exception of sequence numbers (see below). A
 * project that already has seeded history is skipped rather than doubled, so re-running
 * is safe.</p>
 */
@Slf4j
@Component
@Profile("!prod")
@ConditionalOnProperty(name = "app.backfill.projects")
// Ahead of DemoDataSeeder, which is an unordered ApplicationRunner and therefore runs
// last. That ordering is load-bearing rather than incidental: the seeder creates its own
// "Payroll SaaS" and "CRM Platform", and this class matches projects by name and refuses
// when a name is ambiguous. Running afterwards, it would decline to backfill the very
// projects it was pointed at.
@Order(100)
@RequiredArgsConstructor
public class SprintHistoryBackfill implements ApplicationRunner {

    /** Marks a sprint this class made, so a second run tops up rather than duplicates. */
    static final String SEEDED_GOAL = "Deliver the committed scope";

    /**
     * Names for backfilled sprints, distinct from the team's own.
     *
     * <p>The history goes in <em>before</em> what already exists, so the obvious naming
     * — "Sprint 1" upward — collides with the sprints the team numbered themselves. A
     * project whose real work starts at "Sprint 1" would end up with two of them, and
     * the velocity chart labels every bar by name: two bars reading "Sprint 1", six
     * apart, is worse than no chart. Numbering the invented ones downward from zero is
     * the other obvious answer and produces "Sprint -8".</p>
     *
     * <p>So they are marked instead. "Sprint H3" is short enough for a chart axis, sorts
     * where its dates put it, and tells anybody reading the board that this one was
     * manufactured — which is worth knowing about a fixture living in real data. The
     * prefix carries its own separator, so {@code APP_BACKFILL_NAME_PREFIX="Sprint "}
     * drops the marker and produces plain "Sprint 3" if the collision is acceptable.</p>
     */
    private static final String DEFAULT_PREFIX = "Sprint H";

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository memberRepository;
    private final SprintRepository sprintRepository;
    private final SprintHistoryFactory factory;
    private final JdbcTemplate jdbc;

    private final org.springframework.core.env.Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        String setting = environment.getProperty("app.backfill.projects", "");
        List<String> wanted = Arrays.stream(setting.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .toList();
        if (wanted.isEmpty()) {
            return;
        }

        int count = intProperty("app.backfill.sprints", 10);
        for (String name : wanted) {
            try {
                backfill(name, count);
            } catch (RuntimeException ex) {
                // One bad project name must not cost the others their history, and the
                // application must still start: this is a fixture, not a migration.
                log.error("Backfill failed for '{}': {}", name, ex.toString());
            }
        }
    }

    private void backfill(String projectName, int sprintCount) {
        List<Project> matches = projectRepository.findAll().stream()
                .filter(project -> project.getDeletedAt() == null)
                .filter(project -> project.getName().equalsIgnoreCase(projectName))
                .toList();

        if (matches.isEmpty()) {
            log.warn("Backfill: no project named '{}'", projectName);
            return;
        }
        if (matches.size() > 1) {
            // Project names are unique per organization, not globally. Guessing which
            // one was meant would put invented history in somebody else's tenant.
            log.warn("Backfill: '{}' matches {} projects across organizations — rename or "
                    + "narrow the setting", projectName, matches.size());
            return;
        }

        Project project = matches.get(0);
        // Every write below is tenant-filtered, and the filter reads this context. Set
        // as a normal user rather than a system context so the backfill goes through
        // the same guards a request would.
        TenantContext.set(TenantContext.Context.builder()
                .organizationId(project.getOrganizationId())
                .userId(project.getLeadUserId())
                .permissions(Set.of())
                .systemContext(false)
                .build());
        try {
            List<Sprint> existing = sprintRepository
                    .findByProjectIdAndDeletedAtIsNullOrderBySequenceNoDesc(project.getId()).stream()
                    .filter(sprint -> sprint.getStartDate() != null)
                    .toList();

            if (existing.stream().anyMatch(sprint -> SEEDED_GOAL.equals(sprint.getGoal()))) {
                log.info("Backfill: '{}' already has seeded history — skipping", project.getName());
                return;
            }

            // The history has to land before whatever is already there, or the charts
            // read right to left. Absent any existing sprint, it runs up to today.
            LocalDate endsBefore = existing.stream()
                    .map(Sprint::getStartDate)
                    .min(LocalDate::compareTo)
                    .orElse(LocalDate.now());

            SprintHistoryFactory.Spec spec = new SprintHistoryFactory.Spec(
                    sprintCount,
                    project.getDefaultSprintDays(),
                    baseVelocityOf(project),
                    environment.getProperty("app.backfill.name-prefix", DEFAULT_PREFIX),
                    1,
                    endsBefore,
                    membersOf(project.getId()),
                    TITLES);

            factory.build(project.getId(), spec);
            renumberByDate(project.getId());
            log.info("Backfill: added {} completed sprints to '{}' ending before {}",
                    sprintCount, project.getName(), endsBefore);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * The team's own figure when it has one, and a plausible default when it does not.
     *
     * <p>Twenty-two is the median of the estimation model's training set. Using it here
     * keeps a backfilled project consistent with what the model assumes about a project
     * that has told it nothing, rather than inventing a second, different default.</p>
     */
    private int baseVelocityOf(Project project) {
        if (project.getTeamVelocityAvg() != null && project.getTeamVelocityAvg().signum() > 0) {
            return project.getTeamVelocityAvg().intValue();
        }
        return project.getTeamSize() == null ? 22 : Math.max(8, project.getTeamSize() * 5);
    }

    private List<UUID> membersOf(UUID projectId) {
        return memberRepository.findByProjectId(projectId).stream()
                .map(member -> member.getUserId())
                .toList();
    }

    /**
     * Renumbers a project's sprints by start date.
     *
     * <p>The only thing here that touches pre-existing rows, and it is unavoidable.
     * Sequence numbers are handed out by {@code MAX(sequence_no) + 1}, so sprints
     * created now get the highest numbers however far back their dates are — and the
     * velocity report sorts by sequence number, which would draw the oldest sprints on
     * the right. Ordering by date is the invariant the number is supposed to encode.</p>
     *
     * <p>Two passes because {@code uq_sprint_seq} is a non-deferrable unique constraint:
     * a single UPDATE that permutes the values trips it partway through. Sprint names
     * are left alone — "Sprint 23" is what the team called it.</p>
     */
    private void renumberByDate(UUID projectId) {
        jdbc.update("UPDATE sprints SET sequence_no = sequence_no + 100000 WHERE project_id = ?",
                projectId);
        jdbc.update("""
                UPDATE sprints s SET sequence_no = ordered.rank
                FROM (
                    SELECT id, ROW_NUMBER() OVER (
                        ORDER BY start_date NULLS LAST, created_at
                    ) AS rank
                    FROM sprints WHERE project_id = ? AND deleted_at IS NULL
                ) ordered
                WHERE s.id = ordered.id
                """, projectId);
        // Anything soft-deleted was skipped by the ranking and still carries the offset.
        jdbc.update("""
                UPDATE sprints SET sequence_no = sequence_no - 100000
                WHERE project_id = ? AND sequence_no > 100000
                """, projectId);
    }

    private int intProperty(String key, int fallback) {
        try {
            return Integer.parseInt(environment.getProperty(key, String.valueOf(fallback)));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /**
     * Titles rotated across the seeded backlog.
     *
     * <p>Deliberately ordinary delivery work rather than lorem ipsum. The estimation
     * model scores the story text against a 150-term vocabulary, and a backlog of
     * "Story 1", "Story 2" matches none of it — so every seeded story would come back
     * with the same suggestion and the same "the model did not recognise any of this
     * story's wording" caveat, which makes the AI panels look broken.</p>
     */
    private static final List<String> TITLES = List.of(
            "Password reset flow",
            "Export invoices to CSV",
            "Search across the customer list",
            "Rate limiting on the public API",
            "Audit log for permission changes",
            "Bulk import from a spreadsheet",
            "Webhook delivery retries",
            "Two-factor authentication",
            "Pagination on the reports endpoint",
            "Email notification preferences",
            "Archive and restore a record",
            "Session timeout warning",
            "Currency formatting per locale",
            "Attachment upload to object storage",
            "Dashboard loading performance");
}
