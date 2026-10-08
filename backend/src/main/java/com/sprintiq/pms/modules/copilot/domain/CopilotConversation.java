package com.sprintiq.pms.modules.copilot.domain;

import com.sprintiq.pms.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/** A user's thread with the Copilot. Private to that user, not to their role. */
@Entity
@Table(name = "copilot_conversations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CopilotConversation extends TenantAwareEntity {

    public static final String DEFAULT_TITLE = "New conversation";
    public static final int TITLE_MAX = 80;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Builder.Default
    @Column(name = "title", nullable = false, length = TITLE_MAX)
    private String title = DEFAULT_TITLE;

    public boolean hasDefaultTitle() {
        return DEFAULT_TITLE.equals(title);
    }
}
