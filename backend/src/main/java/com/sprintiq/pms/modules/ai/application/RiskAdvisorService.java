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
import java.util.ArrayList;
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

    /**
     * Appended to the system prompt for the question-answering path only.
     *
     * <p>The advisory prompt mandates a four-section structure, which is right for an
     * unprompted report and wrong for "what does confidence mean here?". This relaxes
     * the shape while keeping every rule about not inventing figures.</p>
     */
    private static final String QA_PROMPT_PATH = "prompts/risk-advisor-qa.txt";

    /**
     * How many prior turns are replayed to the provider.
     *
     * <p>The brief is re-sent in full on every question, so the history is only needed
     * for the thread of the conversation itself. Ten turns is enough for a follow-up to
     * make sense and short enough that a long session cannot grow the request without
     * bound.</p>
     */
    private static final int MAX_HISTORY_TURNS = 10;

    private final WebClient.Builder builder;
    private final AppProperties properties;
    private final String systemPrompt;
    private final String qaPrompt;

    public RiskAdvisorService(WebClient.Builder builder, AppProperties properties) {
        this.builder = builder;
        this.properties = properties;
        this.systemPrompt = loadPrompt(SYSTEM_PROMPT_PATH);
        this.qaPrompt = loadPrompt(QA_PROMPT_PATH);
    }

    /** One turn of a conversation. Only the two roles a client is allowed to send. */
    public record Turn(String role, String content) {

        /**
         * Whether this turn may be replayed to the provider.
         *
         * <p>The role allowlist is the point: a client that could put {@code system} in
         * the history would be writing the advisor's instructions, and everything the
         * prompt forbids — inventing figures, overstating a weak model — would be
         * one request away from being switched off.</p>
         */
        boolean isReplayable() {
            return ("user".equals(role) || "assistant".equals(role))
                    && content != null && !content.isBlank();
        }
    }

    private String loadPrompt(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            String prompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            log.info("Loaded advisor prompt {} ({} chars)", path, prompt.length());
            return prompt;
        } catch (IOException ex) {
            log.error("Could not load {} — the advisor will refuse rather than run "
                    + "without its instructions", path, ex);
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
        RiskAdvisorResponse refusal = unavailableReason(risk, systemPrompt);
        if (refusal != null) {
            return refusal;
        }
        return chat(List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", brief(risk, findings))),
                risk.sprintId());
    }

    /**
     * One question about a sprint's risk, answered against that sprint's own figures.
     *
     * <p>The brief is rebuilt and re-sent on every question rather than left to the
     * history. That is the difference between a model answering from this sprint's
     * numbers and one answering from its recollection of numbers it saw eight turns
     * ago — and the second is how a risk assistant starts quoting percentages nobody
     * can find on the page.</p>
     *
     * <p>It is also why this needs no fine-tuning: what the model knows about the sprint
     * arrives with the question, so it is current by construction. A tuned model would
     * have memorised whatever the figures happened to be on the day it was trained.</p>
     */
    public RiskAdvisorResponse ask(SprintRiskResponse risk, List<RiskAdjustmentResponse> findings,
                                   String question, List<Turn> history) {
        RiskAdvisorResponse refusal = unavailableReason(risk, qaPrompt);
        if (refusal != null) {
            return refusal;
        }
        if (question == null || question.isBlank()) {
            return RiskAdvisorResponse.unavailable("Ask a question and the advisor will answer it.");
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt + "\n\n" + qaPrompt));
        // The grounding, as an exchange the model has already had. Sending it as the
        // opening turn rather than folding it into the system prompt keeps instructions
        // and data separable, which is the whole basis for telling the model that the
        // brief is evidence and not orders.
        messages.add(Map.of("role", "user", "content", brief(risk, findings)));
        messages.add(Map.of("role", "assistant", "content",
                "I have this sprint's figures. What would you like to know?"));

        if (history != null) {
            // Newest turns win when the thread outgrows the budget: a follow-up depends
            // on what was just said, not on how the session opened.
            List<Turn> replayable = history.stream().filter(Turn::isReplayable).toList();
            replayable.stream()
                    .skip(Math.max(0, replayable.size() - MAX_HISTORY_TURNS))
                    .forEach(turn -> messages.add(
                            Map.of("role", turn.role(), "content", turn.content())));
        }
        messages.add(Map.of("role", "user", "content", question));

        return chat(messages, risk.sprintId());
    }

    /**
     * Why this call cannot be made, or null if it can.
     *
     * <p>Shared so the advisory and the question path refuse for the same reasons in the
     * same words. They are one feature behind one API key, and a page whose report says
     * the key is missing while its chat box says something else is a page nobody can
     * debug.</p>
     */
    private RiskAdvisorResponse unavailableReason(SprintRiskResponse risk, String prompt) {
        AppProperties.Advisor config = properties.getAi().getAdvisor();

        if (!config.isConfigured()) {
            return RiskAdvisorResponse.unavailable(
                    "No advisor API key is configured. Set ADVISOR_API_KEY to enable "
                    + "narrative advice — note that it sends this sprint's risk summary "
                    + "to an external provider.");
        }
        if (prompt == null) {
            return RiskAdvisorResponse.unavailable(
                    "The advisor's instructions could not be loaded, so it will not run.");
        }
        if (risk == null || risk.overall() == null) {
            return RiskAdvisorResponse.unavailable(
                    "This sprint has not been assessed yet — there is nothing to advise on.");
        }
        return null;
    }

    /**
     * One call to the provider, with failures reported as answers rather than thrown.
     *
     * <p>Shared by both paths. Never throws for a missing key or an unreachable provider:
     * an advisory is commentary on numbers the user can already see, and failing the
     * whole risk screen because a third party is down would make the optional part
     * load-bearing.</p>
     */
    private RiskAdvisorResponse chat(List<Map<String, Object>> messages, UUID sprintId) {
        AppProperties.Advisor config = properties.getAi().getAdvisor();
        Map<String, Object> body = Map.of(
                "model", config.getModel(),
                "max_tokens", config.getMaxTokens(),
                "messages", messages);

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
            log.info("Advisor answered for sprint {} ({} chars, model={})",
                    sprintId, text.length(), model);
            return RiskAdvisorResponse.ok(text, model);

        } catch (WebClientResponseException ex) {
            // The provider's own message is usually the actionable one — a bad key, a
            // model name that does not exist, a quota. Passing the status through beats
            // a generic failure the operator cannot act on.
            log.warn("Advisor call failed for sprint {}: {} {}", sprintId,
                    ex.getStatusCode(), ex.getResponseBodyAsString());
            return RiskAdvisorResponse.unavailable(switch (ex.getStatusCode().value()) {
                case 400 -> "The provider rejected the request — check the configured model name.";
                case 401, 403 -> "The provider rejected the API key.";
                case 429 -> "The provider is rate-limiting this key. Try again shortly.";
                default -> "The advisor provider returned " + ex.getStatusCode() + ".";
            });
        } catch (RuntimeException ex) {
            log.warn("Advisor call failed for sprint {}", sprintId, ex);
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
