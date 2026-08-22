-- M4 v3 predicts effort and prices it with current project rates.
-- The initial planning baseline is retained because it beat the initial ML
-- candidate on the untouched holdout; ML is deployed only for the mid-sprint update.

UPDATE ai_models
SET version = '3.0.0',
    metadata = metadata || jsonb_build_object(
        'outputUnit', 'project currency',
        'target', 'final labor cost from planned task effort',
        'modelType', 'planning effort baseline selected against ML holdout',
        'deploymentMethod', 'planning_baseline',
        'testR2', 0.9192,
        'testMape', 23.45,
        'intervalType', 'holdout MAPE heuristic; not a probability interval',
        'requiredFeatures', jsonb_build_array(
            'team_id',
            'total_tasks',
            'team_size',
            'planned_story_points',
            'planned_effort_minutes',
            'sprint_number',
            'historical_velocity_avg',
            'developer_availability_rate',
            'absence_days_total',
            'concurrent_assignments',
            'skill_match_ratio',
            'avg_seniority_level',
            'blended_hourly_rate',
            'currency')),
    updated_at = now()
WHERE code = 'M4_COST_INITIAL';

UPDATE ai_models
SET version = '3.0.0',
    metadata = metadata || jsonb_build_object(
        'outputUnit', 'project currency',
        'target', 'remaining effort minutes converted to final labor cost',
        'modelType', 'GradientBoostingRegressor',
        'deploymentMethod', 'ml_remaining_effort',
        'testR2', 0.8720,
        'testMape', 28.63,
        'baselineMape', 49.85,
        'intervalType', 'holdout MAPE heuristic; not a probability interval',
        'snapshotLimitation', 'generated partial snapshots from completed aggregates',
        'requiredFeatures', jsonb_build_array(
            'team_id',
            'total_tasks',
            'team_size',
            'planned_story_points',
            'planned_effort_minutes',
            'completed_story_points',
            'total_effort_minutes',
            'elapsed_fraction',
            'sprint_number',
            'historical_velocity_avg',
            'developer_availability_rate',
            'absence_days_total',
            'overtime_hours_total',
            'concurrent_assignments',
            'skill_match_ratio',
            'avg_seniority_level',
            'blended_hourly_rate',
            'actual_cost_so_far',
            'currency')),
    updated_at = now()
WHERE code = 'M4_COST_MID';
