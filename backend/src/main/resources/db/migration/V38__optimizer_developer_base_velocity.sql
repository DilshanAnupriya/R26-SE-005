-- Migration V38: Add base_velocity to optimizer_developers for empirical capacity tracking
ALTER TABLE optimizer_developers
ADD COLUMN IF NOT EXISTS base_velocity DOUBLE PRECISION NOT NULL DEFAULT 0.0;
