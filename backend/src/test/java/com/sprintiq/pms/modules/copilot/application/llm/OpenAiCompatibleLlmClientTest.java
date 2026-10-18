package com.sprintiq.pms.modules.copilot.application.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sprintiq.pms.security.AppProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The wire mapping, against a real local HTTP server rather than a mocked WebClient —
 * the bugs worth catching here are in the JSON shape, and a mock would agree with
 * whatever shape the code happened to produce.
 */
@DisplayName("OpenAI-compatible LLM client")
class OpenAiCompatibleLlmClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private OpenAiCompatibleLlmClient clientAnswering(int status, String responseJson) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        AppProperties properties = new AppProperties();
        AppProperties.Advisor advisor = properties.getAi().getAdvisor();
        advisor.setApiKey("test-key");
        advisor.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        advisor.setModel("test-model");
        advisor.setTimeoutMs(5000);
        return new OpenAiCompatibleLlmClient(WebClient.builder(), properties, mapper);
    }

    private static final ToolSpec RISK_TOOL = new ToolSpec("get_sprint_risk", "Risk for a sprint",
            Map.of("type", "object",
                    "properties", Map.of("sprintId", Map.of("type", "string")),
                    "required", List.of("sprintId")));

    @Test
    @DisplayName("sends tools, tool calls and tool results in the OpenAI shape")
    void requestShape() throws Exception {
        OpenAiCompatibleLlmClient client = clientAnswering(200, """
                {"model":"test-model","choices":[{"message":{"role":"assistant","content":"ok"}}]}
                """);

        client.chat(List.of(
                LlmMessage.system("rules"),
                LlmMessage.user("how risky is sprint 7?"),
                LlmMessage.assistantToolCalls(null,
                        List.of(new ToolCall("call_1", "get_sprint_risk", "{\"sprintId\":\"s7\"}"))),
                LlmMessage.toolResult("call_1", "{\"overall\":0.7}")), List.of(RISK_TOOL));

        JsonNode body = mapper.readTree(lastBody.get());
        assertThat(lastAuth.get()).isEqualTo("Bearer test-key");
        assertThat(body.path("model").asText()).isEqualTo("test-model");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(2048);

        JsonNode tool = body.path("tools").get(0);
        assertThat(tool.path("type").asText()).isEqualTo("function");
        assertThat(tool.path("function").path("name").asText()).isEqualTo("get_sprint_risk");
        assertThat(tool.path("function").path("parameters").path("required").get(0).asText())
                .isEqualTo("sprintId");

        JsonNode assistant = body.path("messages").get(2);
        assertThat(assistant.path("role").asText()).isEqualTo("assistant");
        JsonNode call = assistant.path("tool_calls").get(0);
        assertThat(call.path("id").asText()).isEqualTo("call_1");
        assertThat(call.path("type").asText()).isEqualTo("function");
        assertThat(call.path("function").path("name").asText()).isEqualTo("get_sprint_risk");
        assertThat(call.path("function").path("arguments").asText()).isEqualTo("{\"sprintId\":\"s7\"}");

        JsonNode result = body.path("messages").get(3);
        assertThat(result.path("role").asText()).isEqualTo("tool");
        assertThat(result.path("tool_call_id").asText()).isEqualTo("call_1");
        assertThat(result.path("content").asText()).isEqualTo("{\"overall\":0.7}");
    }

    @Test
    @DisplayName("omits the tools field entirely when no tools are offered")
    void noTools() throws Exception {
        OpenAiCompatibleLlmClient client = clientAnswering(200, """
                {"choices":[{"message":{"role":"assistant","content":"final"}}]}
                """);

        LlmReply reply = client.chat(List.of(LlmMessage.user("hi")), List.of());

        assertThat(mapper.readTree(lastBody.get()).has("tools")).isFalse();
        assertThat(reply.content()).isEqualTo("final");
        assertThat(reply.model()).isEqualTo("test-model");
    }

    @Test
    @DisplayName("maps tool calls in the response")
    void parsesToolCalls() throws Exception {
        OpenAiCompatibleLlmClient client = clientAnswering(200, """
                {"model":"gemini-x","choices":[{"message":{"role":"assistant","content":null,
                  "tool_calls":[{"id":"abc","type":"function",
                    "function":{"name":"get_sprint_risk","arguments":"{\\"sprintId\\":\\"s1\\"}"}}]}}]}
                """);

        LlmReply reply = client.chat(List.of(LlmMessage.user("risk?")), List.of(RISK_TOOL));

        assertThat(reply.hasToolCalls()).isTrue();
        assertThat(reply.content()).isNull();
        assertThat(reply.model()).isEqualTo("gemini-x");
        assertThat(reply.toolCalls()).containsExactly(
                new ToolCall("abc", "get_sprint_risk", "{\"sprintId\":\"s1\"}"));
    }

    @Test
    @DisplayName("hands a tool call's thought signature back to the provider untouched")
    void echoesThoughtSignature() throws Exception {
        // Gemini 3 answers 400 to the follow-up request if the call it made comes back
        // without the signature it attached.
        OpenAiCompatibleLlmClient client = clientAnswering(200, """
                {"model":"gemini-x","choices":[{"message":{"role":"assistant","content":null,
                  "tool_calls":[{"id":"abc","type":"function",
                    "function":{"name":"get_sprint_risk","arguments":"{}"},
                    "extra_content":{"google":{"thought_signature":"sig-123"}}}]}}]}
                """);

        LlmReply reply = client.chat(List.of(LlmMessage.user("risk?")), List.of(RISK_TOOL));
        client.chat(List.of(LlmMessage.user("risk?"),
                LlmMessage.assistantToolCalls(null, reply.toolCalls()),
                LlmMessage.toolResult("abc", "{}")), List.of(RISK_TOOL));

        JsonNode echoed = mapper.readTree(lastBody.get()).path("messages").get(1).path("tool_calls").get(0);
        assertThat(echoed.path("extra_content").path("google").path("thought_signature").asText())
                .isEqualTo("sig-123");
    }

    @Test
    @DisplayName("invents an id when the provider leaves a tool call unnamed")
    void missingToolCallId() throws Exception {
        OpenAiCompatibleLlmClient client = clientAnswering(200, """
                {"choices":[{"message":{"role":"assistant",
                  "tool_calls":[{"type":"function","function":{"name":"list_projects","arguments":"{}"}}]}}]}
                """);

        LlmReply reply = client.chat(List.of(LlmMessage.user("projects?")), List.of(RISK_TOOL));

        assertThat(reply.toolCalls().get(0).id()).isNotBlank();
    }

    @Test
    @DisplayName("turns provider refusals into messages a person can act on")
    void errors() throws Exception {
        assertThatThrownBy(() -> clientAnswering(401, "{}").chat(List.of(LlmMessage.user("x")), List.of()))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("API key");
        stop();
        assertThatThrownBy(() -> clientAnswering(429, "{}").chat(List.of(LlmMessage.user("x")), List.of()))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("rate-limiting");
        stop();
        assertThatThrownBy(() -> clientAnswering(400, "{}").chat(List.of(LlmMessage.user("x")), List.of()))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("model name");
        stop();
        assertThatThrownBy(() -> clientAnswering(404, "{}").chat(List.of(LlmMessage.user("x")), List.of()))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("ADVISOR_MODEL");
    }

    @Test
    @DisplayName("an empty response is unavailable, not a blank answer")
    void emptyChoices() throws Exception {
        OpenAiCompatibleLlmClient client = clientAnswering(200, "{\"choices\":[]}");
        assertThatThrownBy(() -> client.chat(List.of(LlmMessage.user("x")), List.of()))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    @DisplayName("is not configured without a key")
    void notConfigured() {
        AppProperties properties = new AppProperties();
        OpenAiCompatibleLlmClient client =
                new OpenAiCompatibleLlmClient(WebClient.builder(), properties, mapper);
        assertThat(client.isConfigured()).isFalse();
        assertThat(client.modelName()).isEqualTo("gemini-3.5-flash");
    }
}
