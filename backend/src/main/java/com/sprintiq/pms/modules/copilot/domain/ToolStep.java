package com.sprintiq.pms.modules.copilot.domain;

/**
 * One tool the assistant called while writing an answer, as shown to the user.
 *
 * <p>Kept so a reader can see which figures an answer rests on — "used
 * get_sprint_risk" is the difference between a grounded claim and a guess.</p>
 */
public record ToolStep(String tool, boolean ok, String summary) {}
