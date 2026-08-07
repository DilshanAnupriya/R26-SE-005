package com.sprintiq.pms.modules.comments.web.dto;

import com.sprintiq.pms.common.domain.EntityType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CommentDtos {

    private CommentDtos() {
    }

    @Schema(name = "CreateCommentRequest",
            description = "Mentions are written as @[Full Name](userId) and resolved at write time")
    public record CreateCommentRequest(@NotNull EntityType entityType,
                                       @NotNull UUID entityId,
                                       UUID parentCommentId,
                                       @NotBlank @Size(max = 10000) String body) {}

    @Schema(name = "UpdateCommentRequest")
    public record UpdateCommentRequest(@NotBlank @Size(max = 10000) String body) {}

    @Schema(name = "CommentAuthorResponse")
    public record CommentAuthorResponse(UUID id, String fullName, String avatarUrl) {}

    @Schema(name = "CommentResponse")
    public record CommentResponse(UUID id,
                                  EntityType entityType,
                                  UUID entityId,
                                  UUID parentCommentId,
                                  CommentAuthorResponse author,
                                  String body,
                                  String bodyHtml,
                                  List<CommentAuthorResponse> mentions,
                                  boolean edited,
                                  Instant editedAt,
                                  /** Populated for root comments only. */
                                  List<CommentResponse> replies,
                                  boolean canEdit,
                                  Instant createdAt) {}

    @Schema(name = "CommentCountResponse")
    public record CommentCountResponse(EntityType entityType, UUID entityId, long count) {}
}
