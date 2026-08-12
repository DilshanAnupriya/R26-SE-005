-- =====================================================================
-- V5 · Reference data: permissions, system roles, AI model registry
-- =====================================================================
INSERT INTO permissions (code, resource, action, description) VALUES
 ('org:read','org','read','View organization'),
 ('org:update','org','update','Update organization settings'),
 ('org:delete','org','delete','Delete organization'),
 ('member:read','member','read','View members'),
 ('member:invite','member','invite','Invite members'),
 ('member:update','member','update','Change member roles'),
 ('member:remove','member','remove','Remove members'),
 ('role:read','role','read','View roles'),
 ('role:manage','role','manage','Create and edit roles'),
 ('workspace:read','workspace','read','View workspaces'),
 ('workspace:create','workspace','create','Create workspaces'),
 ('workspace:update','workspace','update','Update workspaces'),
 ('workspace:delete','workspace','delete','Delete workspaces'),
 ('project:read','project','read','View projects'),
 ('project:create','project','create','Create projects'),
 ('project:update','project','update','Update projects'),
 ('project:delete','project','delete','Delete projects'),
 ('backlog:read','backlog','read','View backlog'),
 ('backlog:manage','backlog','manage','Create and edit backlog items'),
 ('sprint:read','sprint','read','View sprints'),
 ('sprint:manage','sprint','manage','Plan, start and complete sprints'),
 ('task:read','task','read','View tasks'),
 ('task:manage','task','manage','Create and edit tasks'),
 ('task:assign','task','assign','Assign tasks'),
 ('comment:create','comment','create','Post comments'),
 ('comment:moderate','comment','moderate','Edit or delete any comment'),
 ('attachment:upload','attachment','upload','Upload attachments'),
 ('attachment:delete','attachment','delete','Delete attachments'),
 ('time:log','time','log','Log time entries'),
 ('time:manage','time','manage','Manage others time entries'),
 ('report:read','report','read','View reports and analytics'),
 ('report:export','report','export','Export data'),
 ('ai:predict','ai','predict','Request AI predictions'),
 ('ai:manage','ai','manage','Configure AI models and flags'),
 ('audit:read','audit','read','View audit logs'),
 ('settings:read','settings','read','View settings'),
 ('settings:manage','settings','manage','Manage settings and integrations');

INSERT INTO roles (id, organization_id, name, code, description, system_role, rank) VALUES
 (gen_random_uuid(), NULL, 'Owner',   'OWNER',   'Full control including billing and deletion', TRUE, 10),
 (gen_random_uuid(), NULL, 'Admin',   'ADMIN',   'Manage members, settings and all projects',  TRUE, 20),
 (gen_random_uuid(), NULL, 'Manager', 'MANAGER', 'Manage projects, sprints and reporting',     TRUE, 30),
 (gen_random_uuid(), NULL, 'Member',  'MEMBER',  'Work on assigned items',                      TRUE, 40),
 (gen_random_uuid(), NULL, 'Viewer',  'VIEWER',  'Read-only access',                            TRUE, 50);

-- OWNER + ADMIN: everything (ADMIN excludes org:delete)
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p WHERE r.code = 'OWNER';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ADMIN' AND p.code <> 'org:delete';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'MANAGER' AND p.code IN (
 'org:read','member:read','member:invite','role:read','workspace:read','workspace:create','workspace:update',
 'project:read','project:create','project:update','backlog:read','backlog:manage','sprint:read','sprint:manage',
 'task:read','task:manage','task:assign','comment:create','comment:moderate','attachment:upload','attachment:delete',
 'time:log','time:manage','report:read','report:export','ai:predict','audit:read','settings:read');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'MEMBER' AND p.code IN (
 'org:read','member:read','workspace:read','project:read','backlog:read','backlog:manage','sprint:read',
 'task:read','task:manage','task:assign','comment:create','attachment:upload','time:log','report:read','ai:predict');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'VIEWER' AND p.code IN (
 'org:read','member:read','workspace:read','project:read','backlog:read','sprint:read','task:read','report:read');

-- ---------- AI model registry ------------------------------------------
INSERT INTO ai_models (code, name, description, version, task_type, default_mode, latency_budget_ms, metadata) VALUES
 ('M1_STORY_POINTS','Story Point Estimator',
  'Predicts story points from story text and agile context with uncertainty bounds.',
  '1.0.0','REGRESSION','ZERO_SHOT',4000,
  '{"outputUnit":"points","scale":"fibonacci","supportedModes":["ZERO_SHOT","FEW_SHOT","FINE_TUNED"]}'),
 ('M1_VELOCITY','Sprint Velocity Predictor',
  'Predicts next-sprint velocity from historical performance, capacity and availability.',
  '1.0.0','REGRESSION','ZERO_SHOT',4000,'{"outputUnit":"points"}'),
 ('M2_OVER_BUDGET','Over-Budget Risk Classifier',
  'Predicts the probability that a sprint exceeds its planned effort budget.',
  '1.0.0','CLASSIFICATION','ZERO_SHOT',3000,'{"threshold":0.5}'),
 ('M2_REQUIREMENT_CHANGE','Requirement Change Risk Classifier',
  'Predicts the probability of significant requirement churn during a sprint.',
  '1.0.0','CLASSIFICATION','ZERO_SHOT',3000,'{"threshold":0.5}'),
 ('M3_ALLOCATION','Resource Allocation Optimizer',
  'Stacking ensemble that ranks developers for a task and scores predicted performance.',
  '1.0.0','RANKING','ZERO_SHOT',5000,'{"returnsConsistencyScore":true,"returnsLearningRate":true}'),
 ('M4_COST_INITIAL','Initial Sprint Cost Forecaster',
  'Forecasts sprint cost before execution from team, capacity and historical signals.',
  '1.0.0','REGRESSION','ZERO_SHOT',4000,'{"planningOnly":true}'),
 ('M4_COST_MID','Mid-Sprint Cost Forecaster',
  'Re-forecasts sprint cost mid-execution using actual effort, absences and overtime.',
  '1.0.0','REGRESSION','ZERO_SHOT',4000,'{"planningOnly":true}');
