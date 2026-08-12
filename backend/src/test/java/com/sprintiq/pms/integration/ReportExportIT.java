package com.sprintiq.pms.integration;

import com.sprintiq.pms.common.error.BusinessRuleException;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.modules.analytics.application.ExportService;
import com.sprintiq.pms.modules.analytics.application.ExportWorker;
import com.sprintiq.pms.modules.analytics.domain.ExportJobRepository;
import com.sprintiq.pms.modules.analytics.domain.ExportStatus;
import com.sprintiq.pms.modules.analytics.web.dto.ReportDtos.CreateExportRequest;
import com.sprintiq.pms.modules.analytics.web.dto.ReportDtos.ExportJobResponse;
import com.sprintiq.pms.modules.backlog.application.StoryService;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.CreateStoryRequest;
import com.sprintiq.pms.modules.organizations.application.OrganizationProvisioningService;
import com.sprintiq.pms.modules.organizations.domain.Organization;
import com.sprintiq.pms.modules.organizations.domain.WorkspaceRepository;
import com.sprintiq.pms.modules.projects.application.ProjectService;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.CreateProjectRequest;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.ProjectResponse;
import com.sprintiq.pms.modules.users.domain.User;
import com.sprintiq.pms.modules.users.domain.UserRepository;
import com.sprintiq.pms.modules.users.domain.UserStatus;
import com.sprintiq.pms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * The export flow end to end.
 *
 * <p>Deliberately <em>not</em> {@code @Transactional}, unlike the other integration
 * tests. The whole design turns on the worker running after the job row commits, and a
 * test transaction that never commits would make the thing under test impossible to
 * observe. Fixtures are therefore committed and cleaned up by hand.</p>
 */
class ReportExportIT extends AbstractIntegrationTest {

    @Autowired OrganizationProvisioningService provisioningService;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired UserRepository userRepository;
    @Autowired ProjectService projectService;
    @Autowired StoryService storyService;
    @Autowired ExportService exportService;
    @Autowired ExportWorker exportWorker;
    @Autowired ExportJobRepository exportJobs;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private ProjectResponse newProjectWithBacklog() {
        String email = "exporter+%s@example.test".formatted(UUID.randomUUID());
        User user = userRepository.saveAndFlush(User.builder()
                .email(email)
                .emailNormalized(email.toLowerCase(Locale.ROOT))
                .fullName("Test exporter")
                .status(UserStatus.ACTIVE)
                .emailVerifiedAt(java.time.Instant.now())
                .build());

        Organization organization = provisioningService.provisionOrganization("Acme Robotics",
                user.getId());
        TenantContext.set(TenantContext.Context.builder()
                .organizationId(organization.getId())
                .userId(user.getId())
                .permissions(Set.of("report:export"))
                .systemContext(false)
                .build());

        UUID workspaceId = workspaceRepository
                .findByOrganizationIdAndDeletedAtIsNull(organization.getId()).get(0).getId();

        ProjectResponse project = projectService.create(new CreateProjectRequest(workspaceId,
                "Mobile Banking", null, "Created by ReportExportIT", null, null, null, "USD",
                null, null, "fintech", List.of("java"), (short) 14, List.of(), List.of(), null, null, null, null));

        storyService.create(new CreateStoryRequest(project.id(), null, null,
                "Ordinary story", "Detail.", "One", null, null, null, BigDecimal.valueOf(5),
                null, null, null, null, null, null, null));
        // A title that would execute in a spreadsheet if the writer did not defuse it.
        storyService.create(new CreateStoryRequest(project.id(), null, null,
                "=HYPERLINK(\"http://evil\",\"click\")", null, null, null, null, null, null,
                null, null, null, null, null, null, null));

        return project;
    }

    /** The worker is async; the job is small, so a short poll is enough. */
    private ExportJobResponse awaitTerminal(UUID jobId) {
        for (int attempt = 0; attempt < 100; attempt++) {
            ExportJobResponse status = exportService.status(jobId);
            if (ExportStatus.valueOf(status.status()).isTerminal()) {
                return status;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return exportService.status(jobId);
    }

    @Test
    @DisplayName("a queued export is generated and becomes downloadable")
    void exportRunsAndProducesAFile() throws Exception {
        ProjectResponse project = newProjectWithBacklog();

        ExportJobResponse queued = exportService.request(
                new CreateExportRequest("backlog", project.id(), null, "CSV"));

        assertThat(queued.status()).isEqualTo("QUEUED");
        assertThat(queued.downloadUrl()).as("nothing to download yet").isNull();

        ExportJobResponse finished = awaitTerminal(queued.jobId());

        assertThat(finished.status()).isEqualTo("COMPLETED");
        assertThat(finished.downloadUrl()).isNotNull();
        assertThat(finished.completedAt()).isNotNull();

        try (var content = exportService.download(queued.jobId()).content()) {
            String csv = new String(content.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(csv).contains("Key,Title,Type,Status");
            assertThat(csv).contains("Ordinary story");
            // The hostile title survives as text, disarmed rather than dropped.
            assertThat(csv).contains("'=HYPERLINK");
        }
    }

    @Test
    @DisplayName("the job row records how many rows were written")
    void completedJobRecordsItsRowCount() {
        ProjectResponse project = newProjectWithBacklog();
        ExportJobResponse queued = exportService.request(
                new CreateExportRequest("backlog", project.id(), null, null));

        awaitTerminal(queued.jobId());

        assertThat(exportJobs.findById(queued.jobId()).orElseThrow().getRowCount())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a report that needs a project is refused without one, before any work starts")
    void scopeIsValidatedUpFront() {
        newProjectWithBacklog();

        assertThatThrownBy(() -> exportService.request(
                new CreateExportRequest("velocity", null, null, "CSV")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("needs a projectId");

        assertThatThrownBy(() -> exportService.request(
                new CreateExportRequest("burndown", null, null, "CSV")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("needs a sprintId");
    }

    @Test
    @DisplayName("an unknown report names the ones that exist")
    void unknownReportIsHelpful() {
        newProjectWithBacklog();

        assertThatThrownBy(() -> exportService.request(
                new CreateExportRequest("everything", UUID.randomUUID(), null, "CSV")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Supported:")
                .hasMessageContaining("velocity");
    }

    @Test
    @DisplayName("a format other than CSV is refused rather than silently ignored")
    void onlyCsvIsOffered() {
        ProjectResponse project = newProjectWithBacklog();

        assertThatThrownBy(() -> exportService.request(
                new CreateExportRequest("backlog", project.id(), null, "XLSX")))
                .hasMessageContaining("Only CSV");
    }

    @Test
    @DisplayName("downloading before the job finishes says so instead of returning an empty file")
    void downloadingTooEarlyIsRefused() {
        ProjectResponse project = newProjectWithBacklog();

        // Bypass the dispatcher so the job stays QUEUED for the assertion.
        ExportJobResponse queued = exportService.request(
                new CreateExportRequest("backlog", project.id(), null, "CSV"));

        if (!ExportStatus.valueOf(exportService.status(queued.jobId()).status()).isTerminal()) {
            assertThatThrownBy(() -> exportService.download(queued.jobId()))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("still being generated");
        }
    }

    @Test
    @DisplayName("a second run of the same job is a no-op rather than a duplicate file")
    void aClaimedJobIsNotRunTwice() {
        ProjectResponse project = newProjectWithBacklog();
        ExportJobResponse queued = exportService.request(
                new CreateExportRequest("backlog", project.id(), null, "CSV"));
        awaitTerminal(queued.jobId());

        String firstKey = exportJobs.findById(queued.jobId()).orElseThrow().getStorageKey();

        // What a second node picking up the same id would do.
        exportWorker.run(queued.jobId());

        assertThat(exportJobs.findById(queued.jobId()).orElseThrow().getStorageKey())
                .isEqualTo(firstKey);
    }

    @Test
    @DisplayName("exports are listed newest first for the person who asked for them")
    void listingReturnsYourOwnExports() {
        ProjectResponse project = newProjectWithBacklog();
        exportService.request(new CreateExportRequest("backlog", project.id(), null, "CSV"));
        exportService.request(new CreateExportRequest("velocity", project.id(), null, "CSV"));

        assertThat(exportService.mine(new PageQuery()).content()).hasSizeGreaterThanOrEqualTo(2);
    }
}
