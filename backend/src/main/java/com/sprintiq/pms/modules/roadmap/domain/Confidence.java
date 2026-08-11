package com.sprintiq.pms.modules.roadmap.domain;

/**
 * How sure the owner is that this lands in the quarter it is drawn in.
 *
 * <p>Separate from status on purpose. Status is where the work is; confidence is what
 * the person closest to it believes about where it will end up. A roadmap that only
 * shows status invites everyone downstream to read a date as a promise.</p>
 */
public enum Confidence {
    LOW,
    MEDIUM,
    HIGH
}
