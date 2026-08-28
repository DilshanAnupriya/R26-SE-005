-- Version the M4 explanation contract so forecasts cached before additive cost
-- drivers were introduced cannot be replayed without their local explanation.
--
-- Numbered 28 rather than 24: two branches merged into main both claimed V24, and
-- Flyway refuses to start on the collision. The other V24 had already been applied,
-- so this is the one that moves. Safe to renumber because every statement below is an
-- idempotent UPDATE keyed on model code — a database that applied it as V24 gets the
-- same result running it again as V28.

UPDATE ai_models
SET version = '3.1.0',
    metadata = metadata || jsonb_build_object(
        'driverMethod', 'exact formula components',
        'driverReconciliation', 'signed cost contributions sum to forecast amount',
        'driverFeatures', jsonb_build_array(
            'planned_effort_minutes',
            'blended_hourly_rate')),
    updated_at = now()
WHERE code = 'M4_COST_INITIAL';

UPDATE ai_models
SET version = '3.1.0',
    metadata = metadata || jsonb_build_object(
        'driverMethod', 'reference-based Shapley values in cost units',
        'driverReconciliation', 'actual cost plus ML reference and signed influences sum to forecast amount',
        'driverFeatures', jsonb_build_array(
            'completed_story_points',
            'total_effort_minutes',
            'historical_velocity_avg',
            'developer_availability_rate',
            'absence_days_total',
            'overtime_hours_total',
            'concurrent_assignments',
            'skill_match_ratio',
            'avg_seniority_level')),
    updated_at = now()
WHERE code = 'M4_COST_MID';
