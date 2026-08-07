package com.sprintiq.pms.modules.settings.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IntegrationRepository extends JpaRepository<Integration, UUID> {

    List<Integration> findByWorkspaceId(UUID workspaceId);

    List<Integration> findAllByOrderByProviderAsc();
}
