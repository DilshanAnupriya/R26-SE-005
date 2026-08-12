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
    }

    @Getter @Setter
    public static class Seed {
        private boolean enabled = false;
    }
}
