package com.sprintiq.pms.modules.settings.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, UUID> {

    Optional<FeatureFlag> findByWorkspaceIdAndFlagKey(UUID workspaceId, String flagKey);

    Optional<FeatureFlag> findByWorkspaceIdIsNullAndFlagKey(String flagKey);

    List<FeatureFlag> findByWorkspaceId(UUID workspaceId);

    List<FeatureFlag> findByWorkspaceIdIsNull();
}
