package com.sprintiq.pms.modules.tasks.domain;

/** What sort of work a task represents. Feeds the M3 allocation model's skill matching. */
public enum TaskType {

    TASK,
    DEVELOPMENT,
    DESIGN,
    TESTING,
    REVIEW,
    DOCUMENTATION,
    DEVOPS,
    RESEARCH
}
