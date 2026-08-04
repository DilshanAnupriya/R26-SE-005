package com.sprintiq.pms.modules.organizations.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    Optional<Workspace> findByIdAndDeletedAtIsNull(UUID id);

    Page<Workspace> findByDeletedAtIsNull(Pageable pageable);

    List<Workspace> findByOrganizationIdAndDeletedAtIsNull(UUID organizationId);

    boolean existsByOrganizationIdAndWorkspaceKey(UUID organizationId, String workspaceKey);

    Optional<Workspace> findFirstByOrganizationIdAndDefaultWorkspaceTrue(UUID organizationId);
}
