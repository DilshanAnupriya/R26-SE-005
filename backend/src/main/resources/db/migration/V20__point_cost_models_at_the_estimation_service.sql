-- =====================================================================
-- V17 · Point the sprint cost models at the estimation service
--
-- M4 has two separate model codes because planning-time cost and mid-sprint
-- reforecast use different feature vectors. The endpoint stays in ai_models so
-- changing model hosting does not require a redeploy, and the version bump keeps
-- old mock-era cache entries from being reused.
-- =====================================================================

UPDATE ai_models
SET endpoint_url = 'http://localhost:8000/predict/cost/initial',
    version = '2.0.0',
    metadata = metadata || jsonb_build_object(
        'outputUnit', 'cost amount',
        'target', 'initial sprint planning cost',
        'modelType', 'ExtraTreesRegressor with planning uncertainty band',
        'testMae', 5880.99,
        'testR2', 0.766,
        'testMape', 14.67,
        'trainedOn', 'final_agile_dataset.csv',
        'requiredFeatures', jsonb_build_array(
            'team_id',
            'total_tasks',
            'team_size',
            'planned_story_points',
            'sprint_number',
            'historical_velocity_avg',
            'developer_availability_rate',
            'concurrent_assignments',
            'skill_match_ratio',
            'avg_seniority_level')),
    updated_at = now()
WHERE code = 'M4_COST_INITIAL';

UPDATE ai_models
SET endpoint_url = 'http://localhost:8000/predict/cost/mid-sprint',
    version = '2.0.0',
    metadata = metadata || jsonb_build_object(
        'outputUnit', 'cost amount',
        'target', 'actual sprint cost',
        'modelType', 'RandomForestRegressor with mid-sprint uncertainty band',
        'testMae', 6885.33,
        'testR2', 0.735,
        'testMape', 15.05,
        'trainedOn', 'final_agile_dataset.csv',
        'requiredFeatures', jsonb_build_array(
            'team_id',
            'total_tasks',
            'team_size',
            'planned_story_points',
            'completed_story_points',
            'total_effort_minutes',
            'sprint_number',
            'historical_velocity_avg',
            'developer_availability_rate',
            'absence_days_total',
            'overtime_hours_total',
            'concurrent_assignments',
            'skill_match_ratio',
            'avg_seniority_level')),
    updated_at = now()
WHERE code = 'M4_COST_MID';
