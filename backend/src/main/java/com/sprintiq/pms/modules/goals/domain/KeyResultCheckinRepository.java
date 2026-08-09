package com.sprintiq.pms.modules.goals.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface KeyResultCheckinRepository extends JpaRepository<KeyResultCheckin, UUID> {

    /**
     * Check-in history, newest first.
     *
     * <p>No explicit organization predicate: {@code KeyResultCheckin} extends
     * {@code AppendOnlyTenantEntity}, so the Hibernate tenant filter narrows this
     * automatically. Adding one by hand would suggest the filter is not trusted.</p>
     */
    List<KeyResultCheckin> findByKeyResultIdOrderByRecordedAtDesc(UUID keyResultId);

    default List<KeyResultCheckin> findHistory(UUID keyResultId) {
        return findByKeyResultIdOrderByRecordedAtDesc(keyResultId);
    }
}
