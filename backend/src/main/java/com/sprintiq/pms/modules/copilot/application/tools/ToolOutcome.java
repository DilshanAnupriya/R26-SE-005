package com.sprintiq.pms.modules.copilot.application.tools;

import com.sprintiq.pms.modules.copilot.domain.ToolStep;

/** One tool run: the JSON handed back to the model, and the line shown to the user. */
public record ToolOutcome(String json, ToolStep step) {

    public boolean ok() {
        return step.ok();
    }
}
