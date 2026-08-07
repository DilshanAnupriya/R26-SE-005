package com.sprintiq.pms.modules.settings.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceSettingRepository extends JpaRepository<WorkspaceSetting, UUID> {

    List<WorkspaceSetting> findByWorkspaceId(UUID workspaceId);

    Optional<WorkspaceSetting> findByWorkspaceIdAndSettingKey(UUID workspaceId, String settingKey);
}
