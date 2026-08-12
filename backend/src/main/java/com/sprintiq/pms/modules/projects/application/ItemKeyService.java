package com.sprintiq.pms.modules.projects.application;

import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.projects.domain.Project;
import com.sprintiq.pms.modules.projects.domain.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Mints the human-readable item keys ({@code MBA-142}) that epics, stories and tasks
 * are identified by in conversation, commit messages and tickets.
 *
 * <p>Published as the delivery modules' only way to obtain a key, so the counter has a
 * single writer. Allocation takes a pessimistic write lock on the project row: two
 * concurrent creates would otherwise read the same {@code item_sequence} and mint the
 * same key, and a duplicate key is not something a user can be asked to sort out
 * afterwards — the key is how they refer to the item.</p>
 *
 * <p>The lock is held for a single UPDATE on one row, so contention is limited to
 * simultaneous creates within the same project.</p>
 */
@Service
@RequiredArgsConstructor
public class ItemKeyService {

    private final ProjectRepository projectRepository;

    /**
     * Next key for the project. Joins the caller's transaction so the key is allocated
     * and the item is written atomically — a rolled-back create must not burn a number
     * and leave a visible gap in the sequence.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String allocate(UUID projectId) {
        return lockProject(projectId).nextItemKey();
    }

    /**
     * Allocates {@code count} consecutive keys under one lock. Bulk imports and
     * "split this story into tasks" would otherwise re-take the lock per item.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<String> allocate(UUID projectId, int count) {
        if (count <= 0) {
            return List.of();
        }
        Project project = lockProject(projectId);
        List<String> keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            keys.add(project.nextItemKey());
        }
        return keys;
    }

    private Project lockProject(UUID projectId) {
        Project project = projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
        if (project.isDeleted()) {
            throw new ResourceNotFoundException("Project", projectId);
        }
        return project;
    }
}
