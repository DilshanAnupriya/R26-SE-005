package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.modules.ai.web.dto.AiDtos.RiskAdjustmentResponse;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.RiskAdvisorResponse;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.RiskAssessmentResponse;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.SprintRiskResponse;
import com.sprintiq.pms.security.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns a sprint's risk assessment into advice, using a general-purpose LLM.
 *
 * <p>The only model in this product that is not local. Everything else scores against a
 * bundle in the repository; this posts a summary of the sprint — task titles, driver
 * values, findings — to a third-party API. That is why it is off unless a key is
 * configured, and why the response says which model wrote it.</p>
 *
 * <p>What it is given is deliberately narrow: the numbers the models already produced
 * and the findings already recorded. It is not handed the backlog, the comments, or
 * anything it could use to invent detail. The system prompt forbids inventing figures,
 * and constraining the input is the half of that promise this code can actually keep.</p>
 */
@Slf4j
@Service
public class RiskAdvisorService {

    private static final String SYSTEM_PROMPT_PATH = "prompts/risk-advisor-system.txt";

    private final WebClient.Builder builder;
    private final AppProperties properties;
    private final String systemPrompt;

    public RiskAdvisorService(WebClient.Builder builder, AppProperties properties) {
        this.builder = builder;
        this.properties = properties;
        this.systemPrompt = loadPrompt();
    }

    private String loadPrompt() {
        try (var in = new ClassPathResource(SYSTEM_PROMPT_PATH).getInputStream()) {
            String prompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            log.info("Loaded risk-advisor system prompt ({} chars)", prompt.length());
            return prompt;
        } catch (IOException ex) {
            log.error("Could not load {} — the advisor will refuse rather than run "
                    + "without its instructions", SYSTEM_PROMPT_PATH, ex);
            return null;
        }
    }

    /**
     * Advice for one sprint, or a plain statement of why there is none.
     *
     * <p>Never throws for a missing key or an unreachable provider. An advisory is a
     * commentary on numbers the user can already see; failing the whole risk screen
     * because a third party is down would make the optional part load-bearing.</p>
     */
    public RiskAdvisorResponse advise(SprintRiskResponse risk, List<RiskAdjustmentResponse> findings) {
        AppProperties.Advisor config = properties.getAi().getAdvisor();

        if (!config.isConfigured()) {
            return RiskAdvisorResponse.unavailable(
                    "No advisor API key is configured. Set ADVISOR_API_KEY to enable "
                    + "narrative advice — note that it sends this sprint's risk summary "
                    + "to an external provider.");
        }
        if (systemPrompt == null) {
            return RiskAdvisorResponse.unavailable(
                    "The advisor's instructions could not be loaded, so it will not run.");
        }
        if (risk == null || risk.overall() == null) {
            return RiskAdvisorResponse.unavailable(
                    "This sprint has not been assessed yet — there is nothing to advise on.");
        }

        Map<String, Object> body = Map.of(
                "model", config.getModel(),
                "max_tokens", config.getMaxTokens(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", brief(risk, findings))));

        try {
            ChatResponse response = builder.build().post()
                    .uri(config.getBaseUrl() + config.getPath())
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(ChatResponse.class)
                    .block(Duration.ofMillis(config.getTimeoutMs()));

            String text = response == null ? null : response.firstMessage();
            if (text == null || text.isBlank()) {
                return RiskAdvisorResponse.unavailable(
                        "The advisor returned nothing usable.");
            }
            String model = response.model() == null ? config.getModel() : response.model();
            log.info("Generated risk advisory for sprint {} ({} chars, model={})",
                    risk.sprintId(), text.length(), model);
            return RiskAdvisorResponse.ok(text, model);

        } catch (WebClientResponseException ex) {
            // The provider's own message is usually the actionable one — a bad key, a
            // model name that does not exist, a quota. Passing the status through beats
            // a generic failure the operator cannot act on.
            log.warn("Advisor call failed for sprint {}: {} {}", risk.sprintId(),
                    ex.getStatusCode(), ex.getResponseBodyAsString());
            return RiskAdvisorResponse.unavailable(switch (ex.getStatusCode().value()) {
                case 400 -> "The provider rejected the request — check the configured model name.";
                case 401, 403 -> "The provider rejected the API key.";
                case 429 -> "The provider is rate-limiting this key. Try again shortly.";
                default -> "The advisor provider returned " + ex.getStatusCode() + ".";
            });
        } catch (RuntimeException ex) {
            log.warn("Advisor call failed for sprint {}", risk.sprintId(), ex);
            return RiskAdvisorResponse.unavailable(
                    "The advisor could not be reached. The scores above are unaffected.");
        }
    }

    /**
     * The sprint, as the few facts the advisor is allowed to reason from.
     *
     * <p>Assembled rather than serialised wholesale so nothing reaches the provider that
     * the advice does not need — no ids, no free text beyond the task titles the
     * findings already name.</p>
     */
    private String brief(SprintRiskResponse risk, List<RiskAdjustmentResponse> findings) {
        StringBuilder sb = new StringBuilder();
        var overall = risk.overall();

        sb.append("OVERALL RISK\n");
        sb.append("- Score: ").append(pct(overall.score())).append(" (").append(overall.level()).append(")\n");
        sb.append("- Built on incomplete evidence: ").append(overall.degraded()).append('\n');
        sb.append("- Model weights: ").append(overall.weights()).append('\n');
        sb.append("- Rationale: ").append(overall.rationale()).append("\n\n");

        appendModel(sb, "OVER-BUDGET RISK (well calibrated: 0.91 CV AUC)", risk.overBudget());
        appendModel(sb, "REQUIREMENT-CHANGE RISK (weak: F1 0.46, misses more than it catches)",
                risk.requirementChange());
        appendModel(sb, "COMMUNICATION RISK", risk.communicationCollaboration());

        if (findings != null && !findings.isEmpty()) {
            sb.append("BOARD FINDINGS (each names a task and clears when fixed)\n");
            findings.stream().limit(15).forEach(f ->
                    sb.append("- +").append(f.delta()).append(" [").append(f.riskType())
                      .append("] ").append(f.reason()).append('\n'));
        } else {
            sb.append("BOARD FINDINGS: none standing.\n");
        }
        return sb.toString();
    }

    private void appendModel(StringBuilder sb, String label, RiskAssessmentResponse assessment) {
        sb.append(label).append('\n');
        if (assessment == null) {
            sb.append("- Not scored for this sprint.\n\n");
            return;
        }
        sb.append("- Score: ").append(pct(assessment.riskScore()))
          .append(" (").append(assessment.severity()).append(")\n");
        if (assessment.drivers() != null && !assessment.drivers().isEmpty()) {
            sb.append("- Drivers: ");
            assessment.drivers().stream().limit(5).forEach(d ->
                    sb.append(d.feature()).append(' ').append(d.direction()).append("; "));
            sb.append('\n');
        }
        if (assessment.explanation() != null) {
            sb.append("- Model's own words: ").append(assessment.explanation()).append('\n');
        }
        sb.append('\n');
    }

    private String pct(java.math.BigDecimal score) {
        return score == null ? "unknown"
                : Math.round(score.doubleValue() * 100) + "%";
    }

    /** The slice of the OpenAI-compatible response shape this needs. */
    private record ChatResponse(List<Choice> choices, String model) {
        String firstMessage() {
            if (choices == null || choices.isEmpty() || choices.get(0).message() == null) {
                return null;
            }
            return choices.get(0).message().content();
        }
    }

    private record Choice(Message message) {}

    private record Message(String role, String content) {}
}
