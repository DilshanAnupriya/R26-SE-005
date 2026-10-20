package com.sprintiq.pms.modules.copilot.domain;

/** The changes the Copilot may propose. Each maps to one existing service call. */
public enum ProposalType {
    /** Accept an M1 story-point suggestion, writing it through to the story. */
    ACCEPT_ESTIMATE,
    /** Assign a task to a person, usually one M3 ranked. */
    ASSIGN_TASK,
    /** Pull backlog stories into a sprint. */
    MOVE_TO_SPRINT
}
