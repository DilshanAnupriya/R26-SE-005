package com.sprintiq.pms.modules.projects.domain;

/**
 * A member's function on one project. Deliberately separate from the org-level RBAC
 * {@code Role}: this describes what someone does on the team (and drives assignment
 * defaults and capacity), not what they are permitted to do. Permission checks always
 * go through the org role.
 */
public enum ProjectRole {

    PRODUCT_OWNER,
    SCRUM_MASTER,
    TECH_LEAD,
    DEVELOPER,
    QA,
    DESIGNER,
    STAKEHOLDER;

    /** Stakeholders follow a project without being counted in delivery capacity. */
    public boolean countsTowardCapacity() {
        return this != STAKEHOLDER;
    }
}
