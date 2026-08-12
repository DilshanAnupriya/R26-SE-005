package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.modules.ai.domain.AiModel;
import com.sprintiq.pms.modules.ai.domain.PredictionDriver;
import com.sprintiq.pms.modules.ai.domain.PredictionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Calls the real model services over HTTP.
 *
 * <p>The response contract is deliberately the same shape the mock produces, so nothing
 * above this class can tell the two apart. A model service that answers with a bare
 * number is treated as a degraded response rather than an error — the estimate is still
 * usable, it simply arrives without the explanation the UI would prefer to show, and
 * failing the whole request over a missing narrative would be the wrong trade.</p>
 *
 * <p>Every failure mode maps to a status rather than an exception: timeouts, 4xx, 5xx
 * and unparseable bodies all come back as an unusable {@link InferenceResult} that gets
 * persisted. The caller then degrades the panel to manual input. An AI service being
 * down must never stop someone from estimating a story by hand.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "http")
public class HttpAiModelClient implements AiModelClient {

    private final WebClient webClient;
    private final AiModelRegistry registry;

    public HttpAiModelClient(WebClient.Builder builder, AiModelRegistry registry) {
        this.webClient = builder.build();
        this.registry = registry;
    }

    @Override
    public String providerName() {
        return "http";
    }

    @Override
    public InferenceResult infer(InferenceRequest request) {
        AiModel model = registry.require(request.modelCode());
        if (model.getEndpointUrl() == null || model.getEndpointUrl().isBlank()) {
            return InferenceResult.failure(PredictionStatus.FAILED,
                    "No endpoint configured for " + request.modelCode());
        }

        Map<String, Object> body = Map.of(
                "model", request.modelCode().name(),
                "version", request.modelVersion(),
                "mode", request.mode().name(),
                "features", request.features().asMap());

        try {
            Map<String, Object> response = webClient.post()
                    .uri(model.getEndpointUrl())
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                    .block(request.timeout());

            if (response == null) {
                return InferenceResult.failure(PredictionStatus.TIMEOUT,
                        "No response within %s".formatted(request.timeout()));
            }
            return parse(response);
        } catch (IllegalStateException ex) {
            // Reactor's signal for "block() timed out".
            log.warn("AI model {} exceeded its {}ms budget", request.modelCode(),
                    request.timeout().toMillis());
            return InferenceResult.failure(PredictionStatus.TIMEOUT, ex.getMessage());
        } catch (WebClientResponseException ex) {
            log.warn("AI model {} returned {}", request.modelCode(), ex.getStatusCode());
            return InferenceResult.failure(PredictionStatus.FAILED,
                    "Model service responded %s".formatted(ex.getStatusCode()));
        } catch (RuntimeException ex) {
            log.warn("AI model {} call failed: {}", request.modelCode(), ex.toString());
            return InferenceResult.failure(PredictionStatus.FAILED, ex.getMessage());
        }
    }

    private InferenceResult parse(Map<String, Object> response) {
        BigDecimal value = decimal(response.get("value"));
        String label = string(response.get("label"));
        if (value == null && label == null) {
            return InferenceResult.failure(PredictionStatus.FAILED,
                    "Model response carried neither a value nor a label");
        }

        List<PredictionDriver> drivers = drivers(response.get("drivers"));
        String explanation = string(response.get("explanation"));

        // Usable but incomplete: the number is trustworthy, the narrative is missing.
        PredictionStatus status = drivers.isEmpty() || explanation == null
                ? PredictionStatus.DEGRADED
                : PredictionStatus.SUCCESS;

        return new InferenceResult(value, label, decimal(response.get("confidence")),
                decimal(response.get("lower_bound")), decimal(response.get("upper_bound")),
                drivers, explanation, response, status, null);
    }

    @SuppressWarnings("unchecked")
    private List<PredictionDriver> drivers(Object raw) {
        if (!(raw instanceof List<?> entries)) {
            return List.of();
        }
        List<PredictionDriver> drivers = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> map)) continue;
            Map<String, Object> driver = (Map<String, Object>) map;
            BigDecimal contribution = decimal(driver.get("contribution"));
            if (contribution == null) contribution = BigDecimal.ZERO;
            String direction = string(driver.get("direction"));
            drivers.add(new PredictionDriver(
                    string(driver.get("feature")),
                    string(driver.getOrDefault("label", driver.get("feature"))),
                    contribution,
                    direction != null ? direction
                            : contribution.signum() >= 0 ? PredictionDriver.INCREASE : PredictionDriver.DECREASE,
                    string(driver.get("detail"))));
        }
        return drivers;
    }

    private BigDecimal decimal(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String string(Object value) {
        return value == null ? null : value.toString();
    }
}
