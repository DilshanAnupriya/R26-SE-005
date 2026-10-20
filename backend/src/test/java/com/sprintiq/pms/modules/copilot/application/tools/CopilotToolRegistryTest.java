package com.sprintiq.pms.modules.copilot.application.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.copilot.application.llm.ToolSpec;
import com.sprintiq.pms.security.PermissionService;
import com.sprintiq.pms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Copilot tool registry")
class CopilotToolRegistryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger readCalls = new AtomicInteger();

    private final CopilotTool readTool = SimpleTool.builder()
            .name("read_thing").label("Read a thing").description("Reads")
            .parameters(ToolSchemas.object(Map.of("id", ToolSchemas.uuid("Thing id")), List.of("id")))
            .requiredPermission("thing:read")
            .handler((args, ctx) -> {
                UUID id = ToolArgs.requireUuid(args, "id");
                readCalls.incrementAndGet(); // stands in for the service call
                return Map.of("id", id.toString(), "value", 42);
            })
            .build();

    private final CopilotTool writeTool = SimpleTool.builder()
            .name("write_thing").label("Write a thing").description("Writes")
            .parameters(ToolSchemas.object(Map.of(), List.of()))
            .requiredPermission("thing:manage")
            .handler((args, ctx) -> Map.of("ok", true))
            .build();

    private final CopilotTool missingTool = SimpleTool.builder()
            .name("find_missing").label("Find").description("Throws")
            .parameters(ToolSchemas.object(Map.of(), List.of()))
            .requiredPermission("thing:read")
            .handler((args, ctx) -> { throw ResourceNotFoundException.of("Sprint", UUID.fromString(
                    "00000000-0000-0000-0000-000000000001")); })
            .build();

    private final CopilotTool hugeTool = SimpleTool.builder()
            .name("huge").label("Huge").description("Big output")
            .parameters(ToolSchemas.object(Map.of(), List.of()))
            .requiredPermission("thing:read")
            .handler((args, ctx) -> Map.of("blob", "x".repeat(20_000)))
            .build();

    private final CopilotToolRegistry registry = new CopilotToolRegistry(
            List.of(readTool, writeTool, missingTool, hugeTool), new PermissionService(), mapper);

    private final ToolContext ctx = new ToolContext(UUID.randomUUID(), UUID.randomUUID());

    private static void actingWith(String... permissions) {
        TenantContext.set(TenantContext.Context.builder()
                .organizationId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .permissions(Set.of(permissions))
                .systemContext(false)
                .build());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("offers the model only the tools the user may use")
    void filtersByPermission() {
        actingWith("thing:read");

        assertThat(registry.permittedSpecs()).extracting(ToolSpec::name)
                .containsExactlyInAnyOrder("read_thing", "find_missing", "huge");
    }

    @Test
    @DisplayName("runs a permitted tool and returns its result as JSON")
    void runsTool() throws Exception {
        actingWith("thing:read");
        String id = UUID.randomUUID().toString();

        ToolOutcome outcome = registry.run("read_thing", "{\"id\":\"" + id + "\"}", ctx);

        assertThat(outcome.ok()).isTrue();
        assertThat(mapper.readTree(outcome.json()).path("value").asInt()).isEqualTo(42);
        assertThat(outcome.step().tool()).isEqualTo("read_thing");
        assertThat(outcome.step().summary()).isEqualTo("Read a thing");
    }

    @Test
    @DisplayName("re-checks permission at execution, even for a tool the model names unprompted")
    void rechecksPermission() {
        actingWith("thing:read");

        ToolOutcome outcome = registry.run("write_thing", "{}", ctx);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.json()).contains("error").contains("permission");
    }

    @Test
    @DisplayName("reports an unknown tool as an error the model can read")
    void unknownTool() {
        actingWith("thing:read");
        ToolOutcome outcome = registry.run("drop_database", "{}", ctx);
        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.json()).contains("Unknown tool");
    }

    @Test
    @DisplayName("passes a service's not-found message back rather than failing the turn")
    void serviceErrors() {
        actingWith("thing:read");
        ToolOutcome outcome = registry.run("find_missing", "{}", ctx);
        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.json()).contains("Sprint not found");
        assertThat(outcome.step().summary()).contains("Sprint not found");
    }

    @Test
    @DisplayName("rejects malformed or missing arguments without calling the service")
    void badArguments() {
        actingWith("thing:read");

        assertThat(registry.run("read_thing", "not json", ctx).ok()).isFalse();
        ToolOutcome missing = registry.run("read_thing", "{}", ctx);
        assertThat(missing.ok()).isFalse();
        assertThat(missing.json()).contains("id");
        ToolOutcome notUuid = registry.run("read_thing", "{\"id\":\"sprint-7\"}", ctx);
        assertThat(notUuid.ok()).isFalse();
        assertThat(readCalls.get()).isZero();
    }

    @Test
    @DisplayName("truncates oversized results so one tool cannot flood the context")
    void truncates() {
        actingWith("thing:read");
        ToolOutcome outcome = registry.run("huge", "{}", ctx);
        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.json().length()).isLessThan(CopilotToolRegistry.MAX_RESULT_CHARS + 200);
        assertThat(outcome.json()).contains("truncated");
    }
}
