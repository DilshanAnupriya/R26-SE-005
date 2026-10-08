# SprintIQ Copilot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the SprintIQ Copilot: a saved-conversation LLM assistant at `/copilot` that
answers over projects, sprints and work items and uses M1–M4 as tools, proposing changes
that a person confirms. Spec: `docs/superpowers/specs/2026-10-08-sprintiq-copilot-design.md`.

**Architecture:** A new Spring Boot module `modules/copilot` runs an OpenAI-compatible
tool-calling loop. Each tool calls an existing application service inside the user's
`TenantContext`, so tenancy, permissions and audit apply as they already do. Write tools
create proposal rows; nothing changes until the user confirms the proposal. A Next.js page
under `features/copilot` renders conversations, tool steps and proposal cards.

**Tech Stack:** Java 21, Spring Boot 3, WebClient, JPA + Flyway, hypersistence JsonType,
JUnit 5 + Mockito + AssertJ, ArchUnit; Next.js 16, React 19, TanStack Query, zod, Vitest.

**Build/test commands** (JDK 21 is required; the machine default, JDK 24, breaks Lombok):

```bash
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -q test -Dtest='Copilot*Test,LayeringRulesTest'
cd frontend && pnpm vitest run src/features/copilot && pnpm typecheck
```

Docker is not available on this machine, so the Testcontainers IT (`CopilotIT`) is
written but must be run in CI or wherever Docker is available.

---

## File map

Backend, under `backend/src/main/java/com/sprintiq/pms/modules/copilot/`:

| File | Responsibility |
|---|---|
| `domain/CopilotConversation.java` + `Repository` | conversation row, owned by one user |
| `domain/CopilotMessage.java` + `Repository` | USER/ASSISTANT text, `toolSteps` jsonb |
| `domain/CopilotProposal.java` + `Repository` | pending change, `payload` jsonb, status, expiry |
| `domain/MessageRole`, `ProposalType`, `ProposalStatus`, `ToolStep` | enums + value record |
| `application/llm/LlmClient.java` | `LlmReply chat(List<LlmMessage>, List<ToolSpec>)` |
| `application/llm/LlmMessage`, `LlmReply`, `ToolCall`, `ToolSpec` | provider-neutral records |
| `application/llm/OpenAiCompatibleLlmClient.java` | maps the records to and from `/chat/completions` with `tools` |
| `application/llm/LlmUnavailableException.java` | provider failure with a user-facing message |
| `application/tools/CopilotTool.java` | interface: `name, description, parameters (JSON schema map), requiredPermission, execute(JsonNode args, ToolContext)` |
| `application/tools/ToolContext.java` | conversationId plus a sink for proposals created during the turn |
| `application/tools/CopilotToolRegistry.java` | permitted specs; `run(name, argsJson, ctx)` → JSON string, errors as `{"error":..}`, output truncated to 8,000 characters |
| `application/tools/ProjectTools`, `SprintTools`, `WorkItemTools`, `EstimationTools`, `RiskTools`, `AllocationTools`, `CostTools`, `ProposalTools` | `@Configuration` classes that declare one `CopilotTool` bean per tool |
| `application/CopilotService.java` | agent loop |
| `application/ConversationService.java` | list/get/create/delete, ownership check, title |
| `application/ProposalService.java` | create (validated), confirm, reject, expiry |
| `web/CopilotController.java`, `web/dto/CopilotDtos.java` | REST API |

Plus:
- `resources/db/migration/V31__copilot.sql`
- `resources/prompts/copilot-system.txt`
- `security/AppProperties.java`: add `Ai.copilot`
- `application.yml`: add `app.ai.copilot`
- `LayeringRulesTest`: add a rule that nothing depends on copilot

Frontend:
- `lib/api/copilot-schemas.ts`
- `features/copilot/{api.ts,hooks.ts}`
- `features/copilot/components/{conversation-list,copilot-thread,tool-steps,proposal-card}.tsx` and their tests
- `app/(app)/app/[orgSlug]/copilot/page.tsx`
- add an entry to `components/layout/sidebar.tsx` and `components/layout/command-palette.tsx`
- add `copilot` keys to `lib/api/query-keys.ts`

---

### Task 1: Schema and domain

- [ ] Write `V31__copilot.sql`:
  - three tables, each with `organization_id` (FK organizations, cascade)
  - audit columns `created_at, updated_at, created_by, updated_by, version` on conversations and proposals
  - messages are append-only, with `created_at` only
  - check constraints on role, type and status
  - index `(organization_id, user_id, updated_at DESC)` on conversations
- [ ] Entities:
  - `CopilotConversation` and `CopilotProposal` extend `TenantAwareEntity`
  - `CopilotMessage` extends `AppendOnlyTenantEntity`
  - jsonb columns via `@Type(JsonType.class)`
- [ ] Repositories:
  - `findByUserIdOrderByUpdatedAtDesc`
  - `findByIdAndUserId`
  - `findByConversationIdOrderByCreatedAtAsc`
  - proposals: `findByConversationId` and a `@Lock(PESSIMISTIC_WRITE)` lookup `findForUpdate(id)`
- [ ] Run `LayeringRulesTest`. It must pass, because the tenant-entity rule covers the new entities.
- [ ] Commit `feat(copilot): schema and domain`.

### Task 2: LLM client

- [ ] Write the failing test first, `OpenAiCompatibleLlmClientTest`, using a stubbed `ExchangeFunction` on `WebClient.builder()`. It covers:
  1. The request body carries `tools[].function{name,description,parameters}`, `messages` with `tool_call_id` for tool results, and `tool_calls` on the assistant message.
  2. A response with `choices[0].message.tool_calls` maps to `LlmReply.toolCalls`.
  3. A plain text response maps to `LlmReply.content`.
  4. 401, 429 and 400 map to `LlmUnavailableException` with the same wording the advisor uses.
  5. No key configured → `isConfigured()` is false.
- [ ] Implement the client against `properties.getAi().getAdvisor()`, with `max_tokens` from `getAi().getCopilot().getMaxTokens()`.
- [ ] Run the tests until they pass, then commit `feat(copilot): OpenAI-compatible tool-calling client`.

### Task 3: Tool registry

- [ ] Write the failing test first, `CopilotToolRegistryTest`:
  - only tools whose `requiredPermission` the `TenantContext` holds appear in `permittedSpecs()`
  - `run` re-checks the permission and returns `{"error":"You do not have permission ..."}`
  - an unknown tool returns `{"error":"Unknown tool"}`
  - a tool that throws `ResourceNotFoundException` returns `{"error":"<message>"}`
  - output over 8,000 characters is cut, with a truncation marker appended
  - malformed JSON arguments return an error
- [ ] Implement `CopilotTool`, `ToolContext` and `CopilotToolRegistry`. Results are serialised with the app's `ObjectMapper`.
- [ ] Run the tests until they pass, then commit.

### Task 4: Read tools for system data and M1–M4

Implement the tools as beans that delegate to the existing services:

| Tool | Delegates to |
|---|---|
| `list_projects(query?)` | `ProjectService.search(null,null,null,null,PageQuery{q,size=20})` |
| `get_project_overview(projectId)` | `ProjectService.overview` |
| `list_sprints(projectId,status?)` | `SprintService.search` |
| `get_sprint_summary(sprintId)` | `SprintService.get` + `SprintBacklogService.pointsIn` + `storiesIn` (story id/key/title/status/points/assignee only) |
| `search_work_items(projectId,query?,sprintId?,kind?)` | `StoryService.search` / `TaskService.search`, size 25 |
| `estimate_story_points(storyId)` | `EstimationService.estimate` |
| `forecast_velocity(sprintId)` | `EstimationService.predictVelocity` |
| `get_sprint_risk(sprintId,refresh?)` | `RiskService.latest` (falls back to `assess`) + `adjustments` |
| `rank_developers_for_task(taskId?/storyId?,limit?)` | `AllocationService.rank` |
| `forecast_sprint_cost(sprintId,stage)` | `CostForecastService.forecast` |

- [ ] Write the test first, `CopilotReadToolsTest`, with mocked services. It checks:
  - argument parsing and the service call made (UUID args, enums, defaults)
  - required permissions per tool
  - `get_sprint_risk` falls back to `assess` when `latest` has no overall score
- [ ] Implement the tools, run the tests until they pass, commit.

### Task 5: Proposals

- [ ] Write the test first, `ProposalServiceTest`. It covers:
  - `ACCEPT_ESTIMATE` validation: the prediction exists and is an M1 story-points prediction
  - `ASSIGN_TASK` validation: the task exists (`TaskService.get`)
  - `MOVE_TO_SPRINT` validation: the sprint exists and is not completed or cancelled, and every story is in the sprint's project
  - the summary is written by the server, never taken from LLM text
  - confirm:
    - wrong user → not found
    - not pending → conflict
    - expired → `EXPIRED` and a conflict
    - permission re-checked
    - runs `AiFeedbackService.submit(…ACCEPTED…, apply=true)`, `TaskService.assign(…AI_ACCEPTED…)` or `SprintService.addItems`
    - a service failure becomes `FAILED` with its message
    - a second confirm conflicts and does not run the action again
  - reject → `REJECTED`
- [ ] Implement `ProposalService` and `ProposalTools` (`propose_accept_estimate`, `propose_assign_task`, `propose_move_story_to_sprint`). Each tool returns `{"proposalId", "summary", "status":"PENDING", "note":"The user must confirm"}`.
- [ ] Run the tests until they pass, commit.

### Task 6: Agent loop and conversations

- [ ] Write the test first, `CopilotServiceTest`, with a scripted fake `LlmClient` and a fake registry. It covers:
  - one answer with no tools → user and assistant messages saved, title set from the first message
  - a tool call, then an answer → the tool result is passed back with the matching `tool_call_id`, and `toolSteps` are recorded
  - the round limit → a final call with no tools
  - only the last 20 turns are replayed
  - a message over 4,000 characters is rejected
  - another user's conversation → not found
  - `LlmUnavailableException` → the user message is kept, `AI_SERVICE_UNAVAILABLE` is thrown, and no assistant message is saved
  - no key configured → `AI_SERVICE_UNAVAILABLE`
- [ ] Implement `ConversationService` and `CopilotService`, and write `copilot-system.txt` (data-not-instructions rule, never invent numbers, use tools, propose rather than claim to have changed anything, plain text).
- [ ] Run the tests until they pass, commit.

### Task 7: REST API and config

- [ ] `CopilotDtos`:
  - `CopilotStatusResponse`
  - `ConversationSummary`
  - `ConversationDetail`
  - `MessageResponse` (with `toolSteps` and `proposals`)
  - `ProposalResponse`
  - `SendMessageRequest` (`@NotBlank @Size(max=4000)`)
  - `SendMessageResponse`
- [ ] `CopilotController`: the endpoints listed in the spec, all `@PreAuthorize("@perm.has('ai:predict')")`.
- [ ] `AppProperties.Ai.Copilot{maxToolRounds=6,maxTokens=2048,historyTurns=20}`; add the matching block to `application.yml`.
- [ ] ArchUnit rule: no class outside `..modules.copilot..` depends on `..modules.copilot..`.
- [ ] Write `CopilotIT` (Testcontainers): one user's conversations are invisible to another user and to another org.
- [ ] Run `./mvnw -q test -Dtest='Copilot*Test,LayeringRulesTest'` and compile everything with `./mvnw -q -DskipTests package`, then commit.

### Task 8: Frontend data layer

- [ ] Write zod schemas in `copilot-schemas.ts`.
- [ ] Add `copilotApi` and the hooks:
  - `useCopilotStatus`
  - `useConversations`
  - `useConversation(id)`
  - `useCreateConversation`
  - `useDeleteConversation`
  - `useSendMessage`, which adds the user message optimistically
  - `useConfirmProposal` / `useRejectProposal`, which invalidate the stories, tasks, sprints, backlog and ai keys after a confirm
- [ ] Add `queryKeys.copilot`.
- [ ] Run typecheck, commit.

### Task 9: Frontend UI

- [ ] Write tests first:
  - `proposal-card.test.tsx`: pending shows Confirm/Reject and calls the right handlers; a resolved proposal shows its status; a failed one shows the reason
  - `copilot-thread.test.tsx`: the disabled banner, the empty state with suggestions, rendering of tool steps, dash lines rendered as a list
- [ ] Build the components and the page: a two-column layout (conversation list on the left, thread on the right) that stacks on mobile.
- [ ] Add the sidebar entry "Copilot" under Workspace, before "AI Assistant", with a `MessagesSquare` icon, plus a command-palette entry.
- [ ] Run vitest, typecheck and lint for the changed files, then commit.

### Task 10: Docs and verification

- [ ] Add a Copilot section to the README and a note to `.env.example` saying `ADVISOR_API_KEY` also enables the Copilot.
- [ ] Run the full backend unit test suite and the frontend tests.
- [ ] Run the app (`make infra` needs Docker; skip it if unavailable) and report what was and wasn't verified.
- [ ] Commit.
