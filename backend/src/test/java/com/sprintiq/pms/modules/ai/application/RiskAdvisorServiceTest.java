package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.modules.ai.domain.RiskSeverity;
import com.sprintiq.pms.modules.ai.domain.RiskType;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.OverallRiskResponse;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.RiskAdjustmentResponse;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.RiskAdvisorResponse;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.RiskAssessmentResponse;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.SprintRiskResponse;
import com.sprintiq.pms.security.AppProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The advisor against a stand-in for the provider.
 *
 * <p>This is the one model that leaves the machine, so nothing about its call is
 * exercised by the rest of the suite: the request shape, the bearer header and the
 * shape of the reply are only ever seen by Google. A stub on loopback speaking the
 * OpenAI-compatible dialect Gemini answers in — extra fields and all — is what makes
 * "it is configured" and "it works" different statements.</p>
 */
@DisplayName("Risk advisor")
class RiskAdvisorServiceTest {

    private HttpServer server;
    private AppProperties properties;
    private RiskAdvisorService service;

    /** What the last request carried, so the outbound half can be asserted too. */
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        properties = new AppProperties();
        AppProperties.Advisor advisor = properties.getAi().getAdvisor();
        advisor.setApiKey("test-key");
        advisor.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/openai");
        advisor.setTimeoutMs(5_000);
        service = new RiskAdvisorService(WebClient.builder(), properties);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /** Answer every request with this status and body, recording what came in. */
    private void stub(int status, String body) {
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
    }

    @Test
    @DisplayName("reads the advice out of an OpenAI-shaped reply")
    void happyPath() {
        // Gemini's compatibility layer sends more than the four fields this parses.
        // Carrying the extras here is the point: an unknown field must not fail the call.
        stub(200, """
                {
                  "id": "chatcmpl-abc",
                  "object": "chat.completion",
                  "created": 1735689600,
                  "model": "gemini-3.5-flash",
                  "choices": [
                    {
                      "index": 0,
                      "finish_reason": "stop",
                      "message": {
                        "role": "assistant",
                        "content": "**The call** — this sprint is in trouble."
                      }
                    }
                  ],
                  "usage": {"prompt_tokens": 900, "completion_tokens": 120, "total_tokens": 1020}
                }
                """);

        RiskAdvisorResponse response = service.advise(risk(), List.of(finding()));

        assertThat(response.available()).isTrue();
        assertThat(response.report()).contains("in trouble");
        assertThat(response.model()).isEqualTo("gemini-3.5-flash");
        assertThat(response.reason()).isNull();

        assertThat(lastPath.get()).isEqualTo("/v1beta/openai/chat/completions");
        assertThat(lastAuth.get()).isEqualTo("Bearer test-key");
        assertThat(lastBody.get())
                .contains("\"model\":\"gemini-3.5-flash\"")
                .contains("\"max_tokens\":2048")
                .contains("SPRINT RISK ADVISOR")       // the system prompt went with it
                .contains("OVERALL RISK")              // and so did the brief
                .contains("Wire up the export");       // including the finding's task
    }

    @Test
    @DisplayName("names the key when the provider rejects it")
    void rejectedKey() {
        stub(401, "{\"error\":{\"message\":\"API key not valid\"}}");

        RiskAdvisorResponse response = service.advise(risk(), List.of());

        assertThat(response.available()).isFalse();
        assertThat(response.reason()).contains("rejected the API key");
    }

    @Test
    @DisplayName("points at the model name when the provider rejects the request")
    void rejectedRequest() {
        stub(400, "{\"error\":{\"message\":\"models/gemini-9-flash is not found\"}}");

        RiskAdvisorResponse response = service.advise(risk(), List.of());

        assertThat(response.available()).isFalse();
        assertThat(response.reason()).contains("model name");
    }

    @Test
    @DisplayName("says so rather than throwing when the provider is unreachable")
    void unreachable() {
        server.stop(0);                     // nothing is listening on that port now

        RiskAdvisorResponse response = service.advise(risk(), List.of());

        assertThat(response.available()).isFalse();
        assertThat(response.reason()).contains("could not be reached");
    }

    @Test
    @DisplayName("stays off, and says why, with no key configured")
    void noKey() {
        properties.getAi().getAdvisor().setApiKey("");

        RiskAdvisorResponse response = service.advise(risk(), List.of());

        assertThat(response.available()).isFalse();
        assertThat(response.reason()).contains("ADVISOR_API_KEY");
    }

    @Test
    @DisplayName("does not call the provider for a sprint that was never assessed")
    void neverAssessed() {
        stub(200, "{}");

        RiskAdvisorResponse response = service.advise(
                new SprintRiskResponse(UUID.randomUUID(), null, null, null, null, null), List.of());

        assertThat(response.available()).isFalse();
        assertThat(response.reason()).contains("not been assessed");
        assertThat(lastBody.get()).isNull();
    }

    @Test
    @DisplayName("treats an empty completion as no advice rather than empty advice")
    void emptyCompletion() {
        stub(200, "{\"model\":\"gemini-3.5-flash\",\"choices\":[]}");

        RiskAdvisorResponse response = service.advise(risk(), List.of());

        assertThat(response.available()).isFalse();
        assertThat(response.reason()).contains("nothing usable");
    }

    // ---------------------------------------------------------------- fixtures

    private static SprintRiskResponse risk() {
        return new SprintRiskResponse(
                UUID.randomUUID(),
                assessment(RiskType.OVER_BUDGET, "0.72"),
                assessment(RiskType.REQUIREMENT_CHANGE, "0.31"),
                null,
                RiskSeverity.HIGH,
                new OverallRiskResponse(new BigDecimal("0.61"), RiskSeverity.HIGH, true,
                        Map.of("OVER_BUDGET", new BigDecimal("0.6")),
                        "Two models of three answered."));
    }

    private static RiskAssessmentResponse assessment(RiskType type, String score) {
        return new RiskAssessmentResponse(UUID.randomUUID(), UUID.randomUUID(), type,
                new BigDecimal(score), true, RiskSeverity.HIGH, new BigDecimal("0.8"),
                List.of(), List.of(), "Spend is ahead of burn-down.", UUID.randomUUID(),
                null, null, Instant.now(), BigDecimal.ZERO, new BigDecimal(score),
                RiskSeverity.HIGH);
    }

    private static RiskAdjustmentResponse finding() {
        return new RiskAdjustmentResponse(UUID.randomUUID(), UUID.randomUUID(),
                RiskType.OVER_BUDGET, new BigDecimal("3.0"),
                "'Wire up the export' has had no comment in 6 days",
                Instant.now(), "TASK_SILENT:" + UUID.randomUUID(), "TASK_SILENT", true, false);
    }
}
