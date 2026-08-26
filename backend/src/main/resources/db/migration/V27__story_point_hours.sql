-- =====================================================================
-- V27 · How many hours a story point is worth
--
-- Tasks carry an estimate in minutes; stories carry points. Until now
-- nothing connected the two, so a 5-point story could be broken into
-- forty hours of tasks and nothing anywhere noticed.
--
-- The rate is per project because it is a property of a team's scale,
-- not of the product. A team whose points mean "ideal hours" sets 1;
-- one whose 1-point story is a morning sets 4. The default is 1.0
-- because that is the least surprising reading of "5 points, 5 hours"
-- and because a wrong default that is obvious beats a clever one that
-- is not.
--
-- NOT NULL with a default rather than nullable: every project needs an
-- answer for the budget to mean anything, and "unset" would have to be
-- handled at every call site as either 1.0 or "no budget" — two
-- behaviours nobody would remember to keep in step.
-- =====================================================================

ALTER TABLE projects
    ADD COLUMN hours_per_point NUMERIC(5,2) NOT NULL DEFAULT 1.0
        CONSTRAINT chk_projects_hours_per_point CHECK (hours_per_point > 0 AND hours_per_point <= 100);

COMMENT ON COLUMN projects.hours_per_point IS
    'Hours one story point is worth, for sizing a story''s task budget. Default 1.0.';
