package com.sprintiq.pms.modules.backlog.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.modules.backlog.domain.Epic;
import com.sprintiq.pms.modules.backlog.domain.EpicRepository;
import com.sprintiq.pms.modules.backlog.domain.StoryRepository;
import com.sprintiq.pms.modules.roadmap.application.port.InitiativeWorkSource;
import com.sprintiq.pms.modules.roadmap.application.port.LinkedWork;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tells the roadmap how far through an epic is.
 *
 * <p>The dependency direction is the point: this module knows about the roadmap's
 * port, and the roadmap knows nothing about epics. Either can be removed without the
 * other changing, and the architecture tests fail the build if the roadmap ever
 * reaches for a backlog repository directly.</p>
 */
@Component
@RequiredArgsConstructor
public class EpicWorkSource implements InitiativeWorkSource {

    private final EpicRepository epicRepository;
    private final StoryRepository storyRepository;

    @Override
    public EntityType supports() {
        return EntityType.EPIC;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, LinkedWork> describe(Collection<UUID> entityIds) {
        if (entityIds.isEmpty()) {
            return Map.of();
        }

        List<Epic> epics = epicRepository.findAllById(entityIds).stream()
                .filter(epic -> epic.getDeletedAt() == null)
                .toList();
        if (epics.isEmpty()) {
            return Map.of();
        }

        // One grouped query for every epic on the page rather than one per epic.
        Map<UUID, Object[]> rollUps = new HashMap<>();
        for (Object[] row : storyRepository.rollUpByEpic(epics.stream().map(Epic::getId).toList())) {
            rollUps.put((UUID) row[0], row);
        }

        Map<UUID, LinkedWork> described = new HashMap<>();
        for (Epic epic : epics) {
            Object[] row = rollUps.get(epic.getId());
            described.put(epic.getId(), new LinkedWork(
                    epic.getItemKey(),
                    epic.getTitle(),
                    true,
                    row == null ? 0 : ((Number) row[1]).intValue(),
                    row == null ? 0 : ((Number) row[2]).intValue(),
                    row == null ? BigDecimal.ZERO : (BigDecimal) row[3],
                    row == null ? BigDecimal.ZERO : (BigDecimal) row[4]));
        }
        return described;
    }
}
