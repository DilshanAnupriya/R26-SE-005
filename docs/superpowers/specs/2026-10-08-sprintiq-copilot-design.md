# SprintIQ Copilot — design

Date: 2026-10-08 · Status: approved in brainstorming, awaiting spec review

## Goal

A project-wide conversational assistant built on a hosted LLM that can answer questions
about the organisation's projects, sprints and work items, and that uses all four AI
models (M1 estimation/velocity, M2 risk, M3 allocation, M4 cost) as tools. It may
*propose* changes; nothing changes until the user confirms.

Non-goals: training or fine-tuning a model; autonomous changes; streaming responses
(v1 returns a whole turn); a context-aware side panel (v1 is a dedicated page).

## Decisions taken

| Question | Decision |
|---|---|
| What "LLM" means | Hosted LLM + tool calling, grounded in the system's own data and models |
| Provider | Existing OpenAI-compatible client config (`app.ai.advisor.*`, Gemini by default). Any OpenAI-compatible endpoint that supports `tools` works — OpenAI, Ollama, an Anthropic-compatible gateway |
| Writes | Read freely; writes become *proposals* the user confirms |
| UI | Dedicated `/app/[orgSlug]/copilot` page with a saved conversation list |
| Where the agent runs | Spring Boot, new `modules/copilot` (not the Python ai-service) |

## Architecture

```
/copilot page ──► CopilotController ──► CopilotService (agent loop)
                                          ├─ LlmClient          (OpenAI-compatible chat + tools)
                                          ├─ CopilotToolRegistry ─► CopilotTool beans
                                          │      └─ existing application services
                                          └─ ConversationStore   (Postgres)
                  ProposalController  ──► ProposalService ──► existing application services
```

`modules/copilot` is a top-level orchestration module. Unlike `modules.ai` it is allowed
to depend on the delivery modules' application services (that is its job); a new
ArchUnit rule forbids every other module from depending on `modules.copilot`, so it
stays a leaf.

Tenancy, RBAC and audit come for free because every tool calls the same application
services the controllers call, inside the requesting user's `TenantContext`.

### Package layout

```
modules/copilot/
  application/
    CopilotService.java          agent loop
    LlmClient.java               interface: chat(messages, tools) -> LlmReply
    OpenAiCompatibleLlmClient.java
    ConversationService.java     list/get/create/delete, title, history window
    ProposalService.java         create (from tools), confirm, reject, expire
    tools/
      CopilotTool.java           name, description, JSON schema, requiredPermission, execute(args)
      CopilotToolRegistry.java   collects beans, filters by permission, dispatches
      ProjectTools.java          list_projects, get_project_overview
      SprintTools.java           list_sprints, get_sprint_summary
      WorkItemTools.java         search_work_items
      EstimationTools.java       estimate_story_points (M1), forecast_velocity (M1)
      RiskTools.java             get_sprint_risk (M2)
      AllocationTools.java       rank_developers_for_task (M3)
      CostTools.java             forecast_sprint_cost (M4)
      ProposalTools.java         propose_accept_estimate, propose_assign_task,
                                 propose_move_story_to_sprint
  domain/
    CopilotConversation, CopilotMessage, CopilotProposal (+ repositories, enums)
  web/
    CopilotController, ProposalController, dto/CopilotDtos
resources/prompts/copilot-system.txt
resources/db/migration/V31__copilot.sql
```

## Tools

Every tool declares the permission it needs. Tools the user lacks are **not offered to
the LLM at all**, and the permission is checked again on execution.

| Tool | Args | Backed by | Permission |
|---|---|---|---|
| `list_projects` | `query?` | `ProjectService.search` | `project:read` |
| `get_project_overview` | `projectId` | `ProjectService.overview` + `health` | `project:read` |
| `list_sprints` | `projectId`, `status?` | `SprintService.search` | `sprint:read` |
| `get_sprint_summary` | `sprintId` | `SprintService.get` + `items` + `SprintBacklogService.pointsIn` | `sprint:read` |
| `search_work_items` | `projectId`, `query?`, `sprintId?`, `kind?` (STORY/TASK) | `StoryService.search`, `TaskService.search` | `backlog:read` / `task:read` |
| `estimate_story_points` (M1) | `storyId` | `EstimationService.estimate` | `ai:predict` |
| `forecast_velocity` (M1) | `sprintId` | `EstimationService.predictVelocity` | `ai:predict` |
| `get_sprint_risk` (M2) | `sprintId`, `refresh?` | `RiskService.latest` (falls back to `assess`) + `adjustments` | `ai:predict` |
| `rank_developers_for_task` (M3) | `taskId` or `storyId`, `limit?` | `AllocationService.rank` | `ai:predict` |
| `forecast_sprint_cost` (M4) | `sprintId`, `stage` (INITIAL/MID_SPRINT) | `CostForecastService.forecast` | `ai:predict` |
| `propose_accept_estimate` | `predictionId`, `reason` | creates proposal | `backlog:manage` |
| `propose_assign_task` | `taskId`, `assigneeUserId`, `reason` | creates proposal | `task:assign` |
| `propose_move_story_to_sprint` | `storyIds[]`, `sprintId`, `reason` | creates proposal | `sprint:manage` |

Tool results are the existing response DTOs serialised as JSON and truncated to 8,000
characters, with a `"truncated": true` marker when the limit is hit. A tool that throws
(not found, access denied, model unavailable) returns `{"error": "<message>"}` to the
LLM instead of failing the turn.

Model-quality caveats are written into the tool descriptions, as the risk advisor's
prompt does today: requirement-change risk is weak, and every figure has bounds.

## Agent loop (`CopilotService.send`)

1. Load the conversation and check that it belongs to the current user. Another user's
   conversation returns 404, not 403.
2. Build the messages:
   - the system prompt
   - a context line: today's date, organisation name, user's name
   - the last 20 user/assistant turns from the DB
   - the new user message, at most 4,000 characters

   Tool exchanges from earlier turns are **not** replayed, because results go stale.
   The model calls the tools again when it needs current figures.
3. Call `LlmClient.chat(messages, permittedTools)`.
4. If the reply has `tool_calls`, run each one, append the assistant tool-call message
   and the `tool` results, and go back to step 3. The loop runs at most **6 rounds**.
   After that the model is called once more without tools to force a final answer.
5. Save the user message and the assistant message. The assistant message stores its
   text, the model name, and `tool_steps`: a list of `{tool, ok, summary}` for display.
   It also links any proposals created during the turn.
6. Return the two messages and their proposals.

The provider call happens outside any DB transaction. The two saves share a single
short transaction.

## Proposals

`copilot_proposals` columns:

- `type`: `ACCEPT_ESTIMATE`, `ASSIGN_TASK` or `MOVE_TO_SPRINT`
- `payload` (jsonb)
- `summary`: human text written by the server from the payload, never by the LLM
- `status`: `PENDING`, `CONFIRMED`, `REJECTED`, `FAILED` or `EXPIRED`
- `expires_at`: creation time + 24h
- `resolved_by`, `resolved_at`, `result_message`

When a propose tool is called, the server validates the payload against real data
before saving: the story exists, the user is a candidate, and the sprint is in the same
project. If any check fails, the tool returns an error to the model.

`POST /copilot/proposals/{id}/confirm`:

1. Check the proposal is the user's own, still `PENDING` and not expired.
2. Re-check the action's permission.
3. Run the action:
   - `ACCEPT_ESTIMATE` → `AiFeedbackService.submit(predictionId, ACCEPTED, apply=true)`, which writes the value through to the story and records `ai_feedback`
   - `ASSIGN_TASK` → `TaskService.assign`
   - `MOVE_TO_SPRINT` → `SprintService.addItems`
4. Mark the proposal `CONFIRMED`, or `FAILED` with the error message.

A row lock and the status check make a double-click confirm run the action only once.

`POST /copilot/proposals/{id}/reject` → `REJECTED`. Reading a proposal that is past
`expires_at` returns it as `EXPIRED`.

Why this is safe against prompt injection: task titles and comments reach the model only
as tool *data*. The worst an injected instruction can do is create a proposal. The
summary on that proposal is written by the server, a person must click Confirm, and the
action still goes through the normal permission checks.

## Data model (`V31__copilot.sql`)

All three tables are tenant-scoped: they have `organization_id` and their entities extend
`TenantAwareEntity`, following the existing tables' audit columns.

- `copilot_conversations`
  - `id`, `organization_id`, `user_id`
  - `title` (the first user message, cut to 80 characters)
  - `created_at`, `updated_at`, audit columns
  - index on `(organization_id, user_id, updated_at desc)`
- `copilot_messages`
  - `id`, `organization_id`, `conversation_id` (FK, cascade)
  - `role` (`USER`/`ASSISTANT`), `content` (text), `model`, `tool_steps` (jsonb)
  - `created_at`
- `copilot_proposals`
  - the columns listed in the Proposals section
  - `message_id` (FK) and `conversation_id` (FK)

## API (`/api/v1/copilot`, all require `ai:predict`)

| Method | Path | Purpose |
|---|---|---|
| GET | `/status` | `{enabled, model, reason}`; `reason` is set when the key is missing |
| GET | `/conversations` | The user's conversations, newest first |
| POST | `/conversations` | Create an empty conversation |
| GET | `/conversations/{id}` | Conversation with its messages and proposals |
| DELETE | `/conversations/{id}` | Delete (cascades) |
| POST | `/conversations/{id}/messages` | `{content}`, runs one agent turn, returns `{userMessage, assistantMessage, proposals}` |
| POST | `/proposals/{id}/confirm` | Execute the proposal |
| POST | `/proposals/{id}/reject` | Reject the proposal |

## Error handling

- **No API key:** `/status` returns `enabled:false` with the same "set ADVISOR_API_KEY"
  wording the risk advisor uses. Posting a message returns 503 with the existing `ApiError` code
  `AI_SERVICE_UNAVAILABLE`, and the page shows a banner instead of the input box.
- **Provider error** (401, 429, timeout): the user message is still saved, nothing is
  saved for the assistant, and the API returns 503 `AI_SERVICE_UNAVAILABLE` with the
  advisor-style mapped message. The frontend shows it inline with a Retry button.
- **Tool errors:** returned to the LLM as data. They show as `ok:false` in that turn's
  tool steps.
- **Loop limit hit:** the model is forced to give a final answer, as described in step 4
  of the agent loop.

## Configuration

Reuses `app.ai.advisor.{api-key, base-url, path, model, timeout-ms}`, so the same
`ADVISOR_*` variables power both features. Two new settings:

- `app.ai.copilot.max-tool-rounds`, default 6
- `app.ai.copilot.max-tokens`, default 2048

`.env.example` and the README AI section get a note that the Copilot sends tool results
to the configured provider.

## Frontend

- Route: `app/(app)/app/[orgSlug]/copilot/page.tsx`.
- Entry points: a sidebar item and a command-palette item ("Copilot", Sparkles/Bot icon).
- Code lives in `features/copilot/`:
  - `api.ts`, `hooks.ts` (TanStack Query)
  - schemas in `lib/api/copilot-schemas.ts` (zod)
  - components:
    - `conversation-list.tsx`: new chat, delete
    - `copilot-thread.tsx`: messages, composer, "thinking" state
    - `tool-steps.tsx`: collapsible "Used: get_sprint_risk, forecast_sprint_cost" line
    - `proposal-card.tsx`: server summary plus Confirm/Reject; shows status once resolved
- Answers are plain text, the same as the risk advisor; lines starting with `-` render as
  list items.
- Empty state: suggested prompts such as "Which sprint is most at risk?", "Estimate the
  unestimated stories in <project>" and "Who should take <task>?".
- After a confirm, the page invalidates the query keys for the item it touched (stories,
  tasks, sprint), so other pages update.

## Testing

Backend (JUnit + Mockito; Testcontainers where Postgres is involved):

- `CopilotService`, using a scripted fake `LlmClient`:
  - a single answer with no tools
  - a tool call, then an answer
  - the round limit forces a final answer
  - a tool error is passed back to the model
  - a tool the user isn't permitted never reaches the model
  - only the last 20 turns are replayed
- `CopilotToolRegistry`: permission filtering, re-checking at execution, output truncation.
- `ProposalService`:
  - confirm runs the action exactly once
  - double confirm
  - expired proposal
  - another user's proposal returns 404
  - the action's permission is re-checked
  - a failed action ends as `FAILED`
- `OpenAiCompatibleLlmClient`: request and response mapping for tool calls, using MockWebServer.
- Integration test: conversations are isolated per tenant and per user.
- ArchUnit:
  - nothing depends on `modules.copilot`
  - the existing rules pass, including the `TenantAwareEntity` rule for the new entities

Frontend (Vitest + Testing Library):

- `ProposalCard`: confirm and reject, plus the resolved states
- the thread: sends a message, shows the thinking state, renders tool steps, shows the
  disabled banner
- the zod schemas
