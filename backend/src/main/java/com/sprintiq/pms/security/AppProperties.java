package com.sprintiq.pms.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** Strongly-typed binding for the {@code app.*} configuration tree. */
@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String baseUrl = "http://localhost:3000";
    private Cors cors = new Cors();
    private Security security = new Security();
    private RateLimit rateLimit = new RateLimit();
    private Storage storage = new Storage();
    private Ai ai = new Ai();
    private Seed seed = new Seed();

    @Getter @Setter
    public static class Cors {
        private List<String> allowedOrigins = List.of("http://localhost:3000");
    }

    @Getter @Setter
    public static class Security {
        private Jwt jwt = new Jwt();
        private Duration passwordResetTtl = Duration.ofHours(1);
        private Duration invitationTtl = Duration.ofDays(7);
        private Duration emailVerificationTtl = Duration.ofDays(2);
        private boolean mfaEnabled = false;
        private int maxFailedLogins = 5;
        private Duration lockoutDuration = Duration.ofMinutes(15);
    }

    @Getter @Setter
    public static class Jwt {
        private String secret;
        private String issuer = "sprintiq";
        private Duration accessTokenTtl = Duration.ofMinutes(15);
        private Duration refreshTokenTtl = Duration.ofDays(30);
    }

    @Getter @Setter
    public static class RateLimit {
        private boolean enabled = true;
        private int authRequestsPerMinute = 10;
        private int authenticatedRequestsPerMinute = 600;
        private int anonymousRequestsPerMinute = 60;
    }

    @Getter @Setter
    public static class Storage {
        private String provider = "local";
        private String localPath = "./data/attachments";
        private List<String> allowedContentTypes = List.of();
    }

    /**
     * AI integration. {@code provider} selects the {@code AiModelClient} implementation:
     * {@code mock} runs the deterministic heuristic client, {@code http} calls the real
     * model services. Endpoints and versions live in the database registry rather than
     * here — this tree only configures the caller's behaviour.
     */
    @Getter @Setter
    public static class Ai {
        private boolean enabled = true;
        private String provider = "mock";
        /** Ceiling applied on top of each model's own latency budget. */
        private int timeoutMs = 4000;
        /**
         * How long an identical feature vector may be answered from a previous
         * prediction. Not a staleness window — the hash changes the moment the
         * underlying item does, so this only bounds how long an unchanged item's
         * estimate is reused.
         */
        private Duration cacheTtl = Duration.ofHours(6);
        private int maxBatchSize = 50;
        private Advisor advisor = new Advisor();
        private CostRisk costRisk = new CostRisk();
    }

    /**
     * When a cost overrun starts counting as sprint risk.
     *
     * <p>{@code marginPct} exists because the cost models are not precise. The deployed
     * initial model's held-out MAPE is 23.5% and the mid-sprint one's is 28.6% — a
     * forecast a fifth over budget is inside the model's own typical error and cannot be
     * told apart from one that is exactly right. Charging risk for that would be
     * charging for noise, so only the overrun <em>beyond</em> the margin counts.</p>
     *
     * <p>Configurable rather than hardcoded so a team that retrains to a tighter model
     * can lower the floor without editing code — the number is a property of the model
     * deployed, not of the product.</p>
     */
    @Getter @Setter
    public static class CostRisk {
        /** Overrun below this percentage is treated as noise. */
        private double marginPct = 23.5;
        /** Percentage points of overrun, beyond the margin, per risk point charged. */
        private double pctPerRiskPoint = 5.0;
        /** Floor and ceiling on the charge, so one sprint cannot swamp the ledger. */
        private double minDelta = 2.0;
        private double maxDelta = 12.0;
    }

    /**
     * The narrative risk advisor, which is off unless a key is configured.
     *
     * <p>Off by default deliberately. Every other model in this product runs locally
     * against bundles in the repo; this one posts sprint data — task titles, comment
     * counts, risk drivers — to a third party. That is a decision an operator makes
     * knowingly, not a default they discover afterwards, so an absent key disables the
     * feature rather than silently degrading it.</p>
     */
    @Getter @Setter
    public static class Advisor {
        /** Never commit this. Absent means the feature is simply off. */
        private String apiKey = "";
        private String baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai";
        private String path = "/chat/completions";
        private String model = "gemini-2.0-flash";
        private int maxTokens = 2048;
        private int timeoutMs = 60000;

        public boolean isConfigured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    @Getter @Setter
    public static class Seed {
        private boolean enabled = false;
    }
}
