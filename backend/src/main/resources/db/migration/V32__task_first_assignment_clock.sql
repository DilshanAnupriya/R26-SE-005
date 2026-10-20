-- What the task's clock read when it first got an owner, and the estimate it had then.
--
-- A task added to a sprint with nobody assigned is charged to collaboration risk. Assigning
-- it gives back the share of that charge matching the estimate still left, and extending
-- the estimate afterwards gives back more — so both readings have to outlive the moment of
-- assignment. NULL on every existing row: those were assigned, or not, before the rule.
ALTER TABLE tasks ADD COLUMN unassigned_elapsed_seconds BIGINT;
ALTER TABLE tasks ADD COLUMN estimate_at_assign_minutes INTEGER;
