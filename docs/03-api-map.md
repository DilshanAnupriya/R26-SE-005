# 03 · API Map (v1)

Base: `/api/v1` · Auth: `Authorization: Bearer <jwt>` · Tenant: `X-Organization-Id` (optional override)

Standard query params on every list endpoint:
`page` (0-based), `size` (max 100), `sort` (`field,asc|desc`, repeatable), `q` (free text),
plus documented per-resource filters. Standard envelope for pages:
```json
{ "content": [], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0, "hasNext": false }
```
Standard error:
```json
{ "timestamp":"", "status":422, "code":"VALIDATION_ERROR", "message":"", 
  "path":"/api/v1/...", "correlationId":"", "fieldErrors":[{"field":"","message":""}] }
```

## Auth `/auth`
| Method | Path | Notes |
|---|---|---|
| POST | `/register` | creates user + org, 201 |
| POST | `/login` | 200 + token pair |
| POST | `/refresh` | rotation + reuse detection |
| POST | `/logout` · `/logout-all` | 204 |
| POST | `/password/forgot` · `/password/reset` | always 204 (no user enumeration) |
| POST | `/email/verify` · `/email/resend` | |
| GET | `/me` | principal + memberships + permissions |
| POST | `/mfa/setup` · `/mfa/verify` · `/mfa/challenge` | flag-gated |

## Users `/users`
`GET /` (filters: `status`, `roleId`, `teamId`) · `GET /{id}` · `PATCH /me` · `PATCH /{id}` ·
`PATCH /{id}/status` · `GET /{id}/workload` · `GET /{id}/developer-profile` ·
`PUT /{id}/developer-profile` · `GET /{id}/skills` · `PUT /{id}/skills`

## Organizations `/organizations`
`POST /` · `GET /` (my orgs) · `GET /{id}` · `PATCH /{id}` · `GET /{id}/members` ·
`PATCH /{id}/members/{userId}/role` · `DELETE /{id}/members/{userId}` ·
`POST /{id}/invitations` · `GET /{id}/invitations` · `DELETE /{id}/invitations/{invId}` ·
`POST /invitations/accept` · `GET /{id}/roles` · `POST /{id}/roles` · `PATCH /{id}/roles/{roleId}` ·
`GET /permissions`
### Workspaces `/workspaces`
`POST /` · `GET /` · `GET /{id}` · `PATCH /{id}` · `DELETE /{id}` · `GET /{id}/settings` ·
`PATCH /{id}/settings` · `GET /{id}/feature-flags` · `PATCH /{id}/feature-flags`

## Projects `/projects`
`POST /` · `GET /` (`workspaceId,status,health,leadId`) · `GET /{id}` · `PATCH /{id}` ·
`DELETE /{id}` (soft) · `POST /{id}/restore` · `GET /{id}/overview` · `GET /{id}/health` ·
`GET|POST /{id}/members` · `DELETE /{id}/members/{userId}` ·
`GET|POST /{id}/milestones` · `PATCH|DELETE /milestones/{id}` ·
`GET|POST /{id}/requirements` · `PATCH|DELETE /requirements/{id}` ·
`GET|POST /{id}/workflow-states` · `GET|POST /{id}/labels`

## Backlog `/projects/{projectId}/backlog`
`GET /` (`epicId,status,priority,assigneeId,labelId,sprintId=none`) ·
`POST /rank` (reorder) · `POST /bulk-update`
### Epics `/epics` · Stories `/stories`
`POST|GET /epics` · `GET|PATCH|DELETE /epics/{id}` · `GET /epics/{id}/stories` ·
`POST|GET /stories` · `GET|PATCH|DELETE /stories/{id}` · `POST /stories/{id}/restore` ·
`PATCH /stories/{id}/status` · `PATCH /stories/{id}/assign` · `PATCH /stories/{id}/estimate` ·
`POST /stories/{id}/labels` · `GET|POST /stories/{id}/dependencies` · `GET /stories/{id}/history`

## Sprints `/sprints`
`POST /` · `GET /` (`projectId,status`) · `GET|PATCH|DELETE /{id}` ·
`POST /{id}/start` · `POST /{id}/complete` (spillover strategy) · `POST /{id}/cancel` ·
`GET /{id}/board` · `GET /{id}/items` · `POST /{id}/items` · `DELETE /{id}/items/{storyId}` ·
`GET /{id}/capacity` · `PUT /{id}/capacity` · `GET /{id}/burndown` · `GET /{id}/metrics` ·
`GET|POST /{id}/retrospective` · `GET /{id}/risk` · `GET /{id}/cost-forecast`

## Tasks `/tasks`
`POST /` · `GET /` (`projectId,storyId,sprintId,assigneeId,status,priority,blocked,dueBefore`) ·
`GET|PATCH|DELETE /{id}` · `PATCH /{id}/status` · `PATCH /{id}/assign` · `PATCH /{id}/rank` ·
`POST /{id}/subtasks` · `GET|POST /{id}/watchers` · `POST /{id}/block` · `POST /{id}/unblock` ·
`GET|POST /{id}/time-entries` · `POST /timer/start` · `POST /timer/stop`
### Boards
`GET /projects/{id}/board` (kanban, grouped by workflow state, WIP limits)

## Collaboration
`GET|POST /comments` (`entityType,entityId`) · `PATCH|DELETE /comments/{id}` ·
`GET /activity` (`projectId,entityType,entityId,actorId,from,to`) ·
`GET /notifications` · `PATCH /notifications/{id}/read` · `POST /notifications/read-all` ·
`GET|PUT /notifications/preferences` ·
`POST /attachments` (multipart) · `GET /attachments/{id}` · `GET /attachments/{id}/download` ·
`DELETE /attachments/{id}`

## Reports `/reports`
`GET /velocity` · `/burndown` · `/burnup` · `/cfd` · `/cycle-time` · `/lead-time` ·
`/productivity` · `/completion-rate` · `/workload-balance` · `/cost-trend` · `/risk-summary` ·
`POST /export` (async job) · `GET /export/{jobId}`

## AI `/ai`
| Method | Path |
|---|---|
| POST | `/estimation/story-points` · `/estimation/story-points/batch` |
| POST | `/estimation/velocity` |
| POST | `/risk/over-budget` · `/risk/requirement-change` · `/risk/sprint/{id}/assess` |
| POST | `/allocation/rank` · `/allocation/task/{taskId}/suggest` |
| POST | `/cost/initial` · `/cost/mid-sprint` |
| GET | `/predictions` (`modelCode,entityType,entityId,from,to`) · `/predictions/{id}` |
| POST | `/predictions/{id}/feedback` (ACCEPT/OVERRIDE/DISMISS) |
| GET | `/models` · `PATCH /models/{code}` (admin) |
| GET | `/insights/project/{id}` · `/insights/sprint/{id}` (aggregated dashboard) |

## Governance
`GET /audit-logs` (`actorId,action,entityType,entityId,from,to`) · `GET /audit-logs/{id}` ·
`GET|PATCH /settings` · `GET|POST /integrations` · `GET /admin/recycle-bin` ·
`POST /admin/recycle-bin/{type}/{id}/restore`

Docs: `/swagger-ui.html` · `/v3/api-docs` · Health: `/actuator/health`
