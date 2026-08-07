package com.sprintiq.pms.modules.admin.web;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.admin.application.RecycleBinService;
import com.sprintiq.pms.modules.admin.web.dto.AdminDtos.RecycleBinEntry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Recovery of soft-deleted items within the 30-day window.
 *
 * <p>Behind {@code project:delete} rather than a read permission: seeing what a
 * colleague deleted, and being able to put it back, is an administrative capability, not
 * an ordinary one.</p>
 */
@Tag(name = "Recycle bin", description = "Recovery of deleted items")
@RestController
@RequestMapping(ApiConstants.API_V1 + "/admin/recycle-bin")
@RequiredArgsConstructor
public class RecycleBinController {

    private final RecycleBinService recycleBin;

    @Operation(summary = "Types held in the recycle bin")
    @PreAuthorize("@perm.has('project:delete')")
    @GetMapping("/types")
    public List<EntityType> types() {
        return recycleBin.supportedTypes();
    }

    @Operation(summary = "Deleted items of one type",
            description = "Each entry carries when it was deleted and when it stops being "
                    + "recoverable.")
    @PreAuthorize("@perm.has('project:delete')")
    @GetMapping
    public PageResponse<RecycleBinEntry> list(@RequestParam(required = false) EntityType entityType,
                                              @Valid PageQuery query) {
        return recycleBin.list(entityType, query);
    }

    @Operation(summary = "Restore an item",
            description = "Delegated to the module that owns it, so its own invariants apply.")
    @PreAuthorize("@perm.has('project:delete')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/{entityType}/{entityId}/restore")
    public void restore(@PathVariable EntityType entityType, @PathVariable UUID entityId) {
        recycleBin.restore(entityType, entityId);
    }
}
