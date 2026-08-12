package com.sprintiq.pms.modules.users.domain;

import com.sprintiq.pms.common.domain.BaseEntity;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Global identity. Deliberately NOT tenant-scoped: one email is one login, and
 * access to each organization is granted through {@code org_members}. This is what
 * lets a contractor belong to several customer organizations with one password.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User extends BaseEntity {

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "email_normalized", nullable = false)
    private String emailNormalized;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(name = "avatar_url", length = 512)
    private String avatarUrl;

    @Column(name = "job_title", length = 120)
    private String jobTitle;

    @Builder.Default
    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone = "UTC";

    @Builder.Default
    @Column(name = "locale", nullable = false, length = 16)
    private String locale = "en";

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private UserStatus status = UserStatus.PENDING;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Builder.Default
    @Column(name = "mfa_enabled", nullable = false)
    private boolean mfaEnabled = false;

    @Column(name = "mfa_secret")
    private String mfaSecret;

    @Type(JsonType.class)
    @Column(name = "mfa_recovery_codes", columnDefinition = "jsonb")
    private List<String> mfaRecoveryCodes;

    @Builder.Default
    @Column(name = "failed_login_count", nullable = false)
    private short failedLoginCount = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    public void setEmail(String email) {
        this.email = email == null ? null : email.trim();
        this.emailNormalized = normalizeEmail(email);
    }

    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public void recordSuccessfulLogin() {
        this.failedLoginCount = 0;
        this.lockedUntil = null;
        this.lastLoginAt = Instant.now();
    }

    public void recordFailedLogin(int maxAttempts, java.time.Duration lockDuration) {
        this.failedLoginCount++;
        if (this.failedLoginCount >= maxAttempts) {
            this.lockedUntil = Instant.now().plus(lockDuration);
        }
    }
}
