package com.sprintiq.pms.modules.sprints.domain;

import com.sprintiq.pms.common.domain.TenantAwareEntity;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The team's reflection at the end of a sprint. One per sprint.
 *
 * <p>The three lists are JSONB rather than child tables: they are free-form notes that
 * are only ever read as a whole, in the context of their own retrospective. Action
 * items carry a little structure ({@code text}, {@code ownerId}, {@code done}) so the
 * next retro can show what was actually followed through — the one part of a
 * retrospective with a life beyond the meeting.</p>
 */
@Entity
@Table(name = "retrospectives")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Retrospective extends TenantAwareEntity {

    @Column(name = "sprint_id", nullable = false, updatable = false)
    private UUID sprintId;

    @Builder.Default
    @Type(JsonType.class)
    @Column(name = "went_well", nullable = false, columnDefinition = "jsonb")
    private List<String> wentWell = new ArrayList<>();

    @Builder.Default
    @Type(JsonType.class)
    @Column(name = "went_wrong", nullable = false, columnDefinition = "jsonb")
    private List<String> wentWrong = new ArrayList<>();

    /** Each entry: {@code {"text": ..., "ownerId": ..., "done": false}}. */
    @Builder.Default
    @Type(JsonType.class)
    @Column(name = "action_items", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> actionItems = new ArrayList<>();

    /** 1–5 self-reported team sentiment; trended across sprints. */
    @Column(name = "team_mood")
    private Short teamMood;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "facilitator_id")
    private UUID facilitatorId;
}
