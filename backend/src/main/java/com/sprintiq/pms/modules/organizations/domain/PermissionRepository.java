package com.sprintiq.pms.modules.organizations.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PermissionRepository extends JpaRepository<Permission, UUID> {

    Optional<Permission> findByCode(String code);

    List<Permission> findByCodeIn(List<String> codes);

    List<Permission> findAllByOrderByResourceAscActionAsc();
}
