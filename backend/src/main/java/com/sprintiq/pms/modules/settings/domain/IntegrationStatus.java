package com.sprintiq.pms.modules.settings.domain;

/** Connection state of an outbound integration. */
public enum IntegrationStatus {

    ACTIVE,
    INACTIVE,
    /** Last sync failed. Distinct from INACTIVE, which is a deliberate choice. */
    ERROR
}
