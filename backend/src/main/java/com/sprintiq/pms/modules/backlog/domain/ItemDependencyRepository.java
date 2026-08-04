package com.sprintiq.pms.modules.backlog.domain;

import com.sprintiq.pms.common.domain.EntityType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ItemDependencyRepository extends JpaRepository<ItemDependency, UUID> {

    List<ItemDependency> findBySourceTypeAndSourceId(EntityType sourceType, UUID sourceId);

    List<ItemDependency> findByTargetTypeAndTargetId(EntityType targetType, UUID targetId);

    boolean existsBySourceTypeAndSourceIdAndTargetTypeAndTargetIdAndDependencyType(
            EntityType sourceType, UUID sourceId, EntityType targetType, UUID targetId,
            DependencyType dependencyType);

    /** Every edge touching an item, in either direction — what the item detail panel shows. */
    @Query("""
            SELECT d FROM ItemDependency d
            WHERE (d.sourceType = :type AND d.sourceId = :id)
               OR (d.targetType = :type AND d.targetId = :id)
            """)
    List<ItemDependency> findAllTouching(@Param("type") EntityType type, @Param("id") UUID id);

    /**
     * Outgoing blocking edges only. Cycle detection walks these: a loop among
     * {@code BLOCKS} edges is a schedule that can never start, whereas a loop in
     * {@code RELATES_TO} is just cross-referencing.
     */
    @Query("""
            SELECT d FROM ItemDependency d
            WHERE d.sourceType = :type AND d.sourceId = :id AND d.dependencyType = 'BLOCKS'
            """)
    List<ItemDependency> findBlockingEdgesFrom(@Param("type") EntityType type, @Param("id") UUID id);

    void deleteBySourceTypeAndSourceId(EntityType sourceType, UUID sourceId);

    /**
     * Whether {@code (fromType, fromId)} can already reach {@code (toType, toId)} by
     * following {@code BLOCKS} edges.
     *
     * <p>Used before inserting {@code A blocks B}: if B already reaches A, the new edge
     * would close a loop in which nothing can ever start, and the insert is rejected.
     * Walking the graph in the application would mean one round trip per level, so this
     * is a recursive CTE — {@code UNION} rather than {@code UNION ALL} deduplicates
     * visited nodes, which also stops the recursion terminating only by luck if a cycle
     * somehow already exists in the data.</p>
     *
     * <p>Native SQL bypasses the Hibernate tenant filter, so {@code organization_id} is
     * asserted explicitly in both branches of the recursion.</p>
     */
    @Query(value = """
            WITH RECURSIVE reachable(entity_type, entity_id) AS (
                SELECT d.target_type, d.target_id
                  FROM item_dependencies d
                 WHERE d.organization_id = :organizationId
                   AND d.dependency_type = 'BLOCKS'
                   AND d.source_type = :fromType
                   AND d.source_id = :fromId
                UNION
                SELECT d.target_type, d.target_id
                  FROM item_dependencies d
                  JOIN reachable r
                    ON d.source_type = r.entity_type AND d.source_id = r.entity_id
                 WHERE d.organization_id = :organizationId
                   AND d.dependency_type = 'BLOCKS'
            )
            SELECT EXISTS (
                SELECT 1 FROM reachable
                 WHERE entity_type = :toType AND entity_id = :toId
            )
            """, nativeQuery = true)
    boolean pathExists(@Param("organizationId") UUID organizationId,
                       @Param("fromType") String fromType,
                       @Param("fromId") UUID fromId,
                       @Param("toType") String toType,
                       @Param("toId") UUID toId);
}
