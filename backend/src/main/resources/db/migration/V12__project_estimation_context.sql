-- =====================================================================
-- V12 · Estimation context on a project
--
-- The story-point model takes four inputs that describe the team rather
-- than the story: how many people, how experienced, how much they have
-- historically delivered per sprint, and how much of what they commit to
-- they actually finish. Those are properties of a project, asked once at
-- creation, not re-typed on every estimate.
--
-- The other model inputs already exist here: domain, tech_stack and
-- default_sprint_days. This migration adds only what was missing.
--
-- All nullable. A project created before this migration — or by someone
-- who skipped the optional section — still estimates; the service falls
-- back to the training medians and says so in the explanation, which is
-- honest in a way that a silent zero would not be. team_velocity_avg = 0
-- means "this team has never delivered anything" and the model prices
-- accordingly, so absent must not be stored as zero.
-- =====================================================================

ALTER TABLE projects
    ADD COLUMN team_size               SMALLINT,
    ADD COLUMN dev_experience_level    VARCHAR(16),
    ADD COLUMN team_velocity_avg       NUMERIC(6,2),
    ADD COLUMN recent_completion_rate  NUMERIC(4,3);

-- Ranges mirror the training data (n=3000). A value outside them is not
-- rejected because it is impossible, but because the model has never seen
-- one and would extrapolate rather than estimate.
ALTER TABLE projects
    ADD CONSTRAINT ck_project_team_size
        CHECK (team_size IS NULL OR team_size BETWEEN 1 AND 50),
    ADD CONSTRAINT ck_project_dev_experience
        CHECK (dev_experience_level IS NULL
               OR dev_experience_level IN ('junior','mid','senior')),
    ADD CONSTRAINT ck_project_velocity
        CHECK (team_velocity_avg IS NULL OR team_velocity_avg BETWEEN 0 AND 500),
    -- A proportion, not a percentage. Storing 85 where 0.85 was meant would
    -- read as a team finishing eighty-five times what it commits to.
    ADD CONSTRAINT ck_project_completion_rate
        CHECK (recent_completion_rate IS NULL OR recent_completion_rate BETWEEN 0 AND 1);

COMMENT ON COLUMN projects.team_size IS
    'Delivery team size, an input to the story-point model.';
COMMENT ON COLUMN projects.dev_experience_level IS
    'Predominant experience level: junior, mid or senior. Model input.';
COMMENT ON COLUMN projects.team_velocity_avg IS
    'Average story points completed per sprint. Seeded at creation, and the
     figure the model uses until enough sprints have closed to measure it.';
COMMENT ON COLUMN projects.recent_completion_rate IS
    'Proportion of committed work finished in recent sprints, 0..1.';
