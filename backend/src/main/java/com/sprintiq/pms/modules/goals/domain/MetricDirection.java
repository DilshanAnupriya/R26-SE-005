package com.sprintiq.pms.modules.goals.domain;

/**
 * Which way is good.
 *
 * <p>Load-bearing, not cosmetic: plenty of results improve by going down — defect
 * count, cycle time, churn. Without this, driving defects from 40 to 10 computes as
 * negative progress and the goal reports as failing while it succeeds.</p>
 */
public enum MetricDirection {
    INCREASE,
    DECREASE
}
