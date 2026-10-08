package com.sprintiq.pms.modules.copilot.domain;

import com.sprintiq.pms.common.domain.AppendOnlyTenantEntity;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One visible turn. Append-only: an answer is a record of what was said. */
@Entity
@Table(name = "copilot_messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CopilotMessage extends AppendOnlyTenantEntity {

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16, updatable = false)
    private MessageRole role;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    /** Which provider model wrote an assistant message; null for the user's own. */
    @Column(name = "model", length = 120)
    private String model;

    @Builder.Default
    @Type(JsonType.class)
    @Column(name = "tool_steps", nullable = false, columnDefinition = "jsonb")
    private List<ToolStep> toolSteps = new ArrayList<>();
}
