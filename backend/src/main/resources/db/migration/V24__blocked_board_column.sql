-- =====================================================================
-- V24 · A Blocked column on every board.
--
-- TaskStatus.BLOCKED, the `blocked` flag on a task, and the over-budget
-- charge a block incurs have all existed since the delivery core. What
-- did not exist was anywhere on the board to put a blocked card, so the
-- status was reachable only through the task dialog and most teams never
-- reached it at all. The risk signal was wired to an event nobody could
-- easily raise.
--
-- Seeded for new projects in WorkflowStateService; this backfills the
-- projects that already exist, so the column is not a thing only new
-- boards get.
-- =====================================================================

-- Category IN_PROGRESS, not DONE. Blocked work is work that has stopped,
-- not work that is finished, and the burndown, the completed-points sum
-- and every story rollup read the category rather than the position. The
-- column sits last on the board; that is layout, and layout only.
INSERT INTO workflow_states (organization_id, project_id, name, code, category, position, color)
SELECT p.organization_id,
       p.id,
       'Blocked',
       'BLOCKED',
       'IN_PROGRESS',
       -- After whatever the project already has, rather than a literal 5.
       -- Teams rename, reorder and delete columns freely, so a project may
       -- well have three or nine, and a fixed position would collide.
       (SELECT COALESCE(MAX(w.position), -1) + 1
          FROM workflow_states w
         WHERE w.project_id = p.id),
       '#ef4444'
  FROM projects p
 WHERE NOT EXISTS (SELECT 1
                     FROM workflow_states w
                    WHERE w.project_id = p.id
                      AND w.code = 'BLOCKED');

-- Tasks already sitting at BLOCKED status have no column to be drawn in —
-- the board groups by workflow_state_id, so they were invisible on it.
-- Now that the column exists, put them there.
UPDATE tasks t
   SET workflow_state_id = w.id
  FROM workflow_states w
 WHERE w.project_id = t.project_id
   AND w.code = 'BLOCKED'
   AND t.status = 'BLOCKED'
   AND (t.workflow_state_id IS NULL OR t.workflow_state_id <> w.id);
