package com.sprintiq.pms.modules.copilot.application.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sprintiq.pms.security.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chat completions with tools, over any OpenAI-compatible endpoint.
 *
 * <p>Gemini, OpenAI, a local Ollama and most gateways all speak this shape, so the
 * provider is a matter of base URL and model name. It shares its connection settings
 * with the risk advisor deliberately: sending sprint data to a third party is one
 * decision, made once, with one key.</p>
 */
@Slf4j
@Component
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final WebClient.Builder builder;
    private final AppProperties properties;
    private final ObjectMapper mapper;

    public OpenAiCompatibleLlmClient(WebClient.Builder builder, AppProperties properties, ObjectMapper mapper) {
        this.builder = builder;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    public boolean isConfigured() {
        return properties.getAi().getAdvisor().isConfigured();
    }

    @Override
    public String modelName() {
        return properties.getAi().getAdvisor().getModel();
    }

    @Override
    public LlmReply chat(List<LlmMessage> messages, List<ToolSpec> tools) {
        AppProperties.Advisor config = properties.getAi().getAdvisor();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModel());
        body.put("max_tokens", properties.getAi().getCopilot().getMaxTokens());
        body.put("messages", messages.stream().map(this::toWire).toList());
        if (tools != null && !tools.isEmpty()) {
            // Omitted rather than sent empty: some compatible servers reject "tools": [].
            body.put("tools", tools.stream().map(OpenAiCompatibleLlmClient::toWire).toList());
        }

        JsonNode response;
        try {
            response = builder.build().post()
                    .uri(config.getBaseUrl() + config.getPath())
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofMillis(config.getTimeoutMs()));
        } catch (WebClientResponseException ex) {
            log.warn("Copilot provider call failed: {} {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new LlmUnavailableException(switch (ex.getStatusCode().value()) {
                case 400 -> "The provider rejected the request — check the configured model name "
                        + "and that it supports tool calling.";
                case 401, 403 -> "The provider rejected the API key.";
                case 429 -> "The provider is rate-limiting this key. Try again shortly.";
                default -> "The AI provider returned " + ex.getStatusCode() + ".";
            }, ex);
        } catch (RuntimeException ex) {
            log.warn("Copilot provider unreachable", ex);
            throw new LlmUnavailableException("The AI provider could not be reached.", ex);
        }

        return fromWire(response, config.getModel());
    }

    private Map<String, Object> toWire(LlmMessage message) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("role", message.role());
        // Null content is legal (and expected) on an assistant turn that only calls tools.
        wire.put("content", message.content());
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            wire.put("tool_calls", message.toolCalls().stream().map(call -> Map.of(
                    "id", call.id(),
                    "type", "function",
                    "function", Map.of("name", call.name(), "arguments", call.argumentsJson())))
                    .toList());
        }
        if (message.toolCallId() != null) {
            wire.put("tool_call_id", message.toolCallId());
        }
        return wire;
    }

    private static Map<String, Object> toWire(ToolSpec tool) {
        return Map.of("type", "function", "function", Map.of(
                "name", tool.name(),
                "description", tool.description(),
                "parameters", tool.parameters()));
    }

    private LlmReply fromWire(JsonNode response, String configuredModel) {
        JsonNode message = response == null ? null : response.path("choices").path(0).path("message");
        if (message == null || message.isMissingNode()) {
            throw new LlmUnavailableException("The AI provider returned nothing usable.");
        }

        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            JsonNode function = call.path("function");
            String id = call.path("id").asText("");
            // Some compatible servers omit the id. The loop needs one to pair the result
            // with the request, so one is made up — it only has to be unique per turn.
            if (id.isBlank()) id = "call_" + UUID.randomUUID();
            JsonNode arguments = function.path("arguments");
            String argumentsJson = arguments.isTextual() ? arguments.asText()
                    : arguments.isMissingNode() || arguments.isNull() ? "{}" : arguments.toString();
            calls.add(new ToolCall(id, function.path("name").asText(), argumentsJson));
        }

        JsonNode contentNode = message.path("content");
        String content = contentNode.isTextual() ? contentNode.asText() : null;
        if (calls.isEmpty() && (content == null || content.isBlank())) {
            throw new LlmUnavailableException("The AI provider returned nothing usable.");
        }

        String model = response.path("model").asText("");
        return new LlmReply(content, calls, model.isBlank() ? configuredModel : model);
    }
}
