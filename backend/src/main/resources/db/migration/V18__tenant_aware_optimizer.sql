-- Migration to add organization_id to all optimizer tables for multi-tenant isolation

ALTER TABLE optimizer_developers
    ADD COLUMN organization_id UUID REFERENCES organizations(id) ON DELETE CASCADE;

ALTER TABLE optimizer_developer_profiles
    ADD COLUMN organization_id UUID REFERENCES organizations(id) ON DELETE CASCADE;

ALTER TABLE optimizer_sprints
    ADD COLUMN organization_id UUID REFERENCES organizations(id) ON DELETE CASCADE;

ALTER TABLE optimizer_tasks
    ADD COLUMN organization_id UUID REFERENCES organizations(id) ON DELETE CASCADE;

ALTER TABLE optimizer_allocations
    ADD COLUMN organization_id UUID REFERENCES organizations(id) ON DELETE CASCADE;

-- Backfill any existing optimizer rows with the first organization if one exists
DO $$
DECLARE
    first_org_id UUID;
BEGIN
    SELECT id INTO first_org_id FROM organizations ORDER BY created_at ASC LIMIT 1;
    IF first_org_id IS NOT NULL THEN
        UPDATE optimizer_developers SET organization_id = first_org_id WHERE organization_id IS NULL;
        UPDATE optimizer_developer_profiles SET organization_id = first_org_id WHERE organization_id IS NULL;
        UPDATE optimizer_sprints SET organization_id = first_org_id WHERE organization_id IS NULL;
        UPDATE optimizer_tasks SET organization_id = first_org_id WHERE organization_id IS NULL;
        UPDATE optimizer_allocations SET organization_id = first_org_id WHERE organization_id IS NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_optimizer_developers_org ON optimizer_developers(organization_id);
CREATE INDEX IF NOT EXISTS idx_optimizer_developer_profiles_org ON optimizer_developer_profiles(organization_id);
CREATE INDEX IF NOT EXISTS idx_optimizer_sprints_org ON optimizer_sprints(organization_id);
CREATE INDEX IF NOT EXISTS idx_optimizer_tasks_org ON optimizer_tasks(organization_id);
CREATE INDEX IF NOT EXISTS idx_optimizer_allocations_org ON optimizer_allocations(organization_id);
