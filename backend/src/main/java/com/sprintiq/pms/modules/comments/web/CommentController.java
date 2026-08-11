package com.sprintiq.pms.modules.comments.web;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.comments.application.CommentService;
import com.sprintiq.pms.modules.comments.web.dto.CommentDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Comments", description = "Threaded discussion on any work item")
@RestController
@RequestMapping(ApiConstants.API_V1 + "/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    @Operation(summary = "Comments on an item, with their replies")
    @PreAuthorize("@perm.has('project:read')")
    @GetMapping
    public PageResponse<CommentResponse> list(@RequestParam EntityType entityType,
                                              @RequestParam UUID entityId,
                                              @Valid PageQuery query) {
        return commentService.list(entityType, entityId, query);
    }

    @Operation(summary = "Comment count for a badge")
    @PreAuthorize("@perm.has('project:read')")
    @GetMapping("/count")
    public CommentCountResponse count(@RequestParam EntityType entityType,
                                      @RequestParam UUID entityId) {
        return commentService.count(entityType, entityId);
    }

    @Operation(summary = "Post a comment or a reply",
            description = "Mentions written as @[Name](userId) notify those people. Replies are "
                    + "one level deep; replying to a reply joins the same thread.")
    @PreAuthorize("@perm.has('comment:create')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public CommentResponse create(@Valid @RequestBody CreateCommentRequest request) {
        return commentService.create(request);
    }

    @Operation(summary = "Edit your own comment",
            description = "Authors only. comment:moderate grants removal, not rewriting.")
    @PreAuthorize("@perm.has('comment:create')")
    @PatchMapping("/{commentId}")
    public CommentResponse update(@PathVariable UUID commentId,
                                  @Valid @RequestBody UpdateCommentRequest request) {
        return commentService.update(commentId, request);
    }

    @Operation(summary = "Delete a comment",
            description = "Your own, or anyone's with comment:moderate. Replies go with it.")
    @PreAuthorize("@perm.has('comment:create')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{commentId}")
    public void delete(@PathVariable UUID commentId) {
        commentService.delete(commentId);
    }
}
