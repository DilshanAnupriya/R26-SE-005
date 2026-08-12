package com.sprintiq.pms.modules.auth.application;

import com.sprintiq.pms.modules.users.domain.User;
import com.sprintiq.pms.security.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Transactional auth emails. Sending is asynchronous and failures are logged rather than
 * thrown: a mail outage must not fail a registration or a password reset request.
 * (Templated HTML via Thymeleaf replaces these plain bodies in Phase 4.)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthNotificationService {

    private final JavaMailSender mailSender;
    private final AppProperties properties;

    @Async("eventExecutor")
    public void sendEmailVerification(User user, String rawToken) {
        String link = "%s/verify-email?token=%s".formatted(properties.getBaseUrl(), encode(rawToken));
        send(user.getEmail(), "Confirm your SprintIQ email",
                """
                Hi %s,

                Confirm your email address to finish setting up your SprintIQ account:
                %s

                This link expires in %d hours. If you didn't create an account, ignore this message.
                """.formatted(user.getFullName(), link,
                        properties.getSecurity().getEmailVerificationTtl().toHours()));
    }

    @Async("eventExecutor")
    public void sendPasswordReset(User user, String rawToken) {
        String link = "%s/reset-password?token=%s".formatted(properties.getBaseUrl(), encode(rawToken));
        send(user.getEmail(), "Reset your SprintIQ password",
                """
                Hi %s,

                Use the link below to choose a new password:
                %s

                The link expires in %d minutes and can only be used once.
                If you didn't request this, no action is needed — your password has not changed.
                """.formatted(user.getFullName(), link,
                        properties.getSecurity().getPasswordResetTtl().toMinutes()));
    }

    @Async("eventExecutor")
    public void sendPasswordChanged(User user) {
        send(user.getEmail(), "Your SprintIQ password was changed",
                """
                Hi %s,

                Your password was just changed and all other sessions were signed out.
                If this wasn't you, reset your password immediately and contact your administrator.
                """.formatted(user.getFullName()));
    }

    @Async("eventExecutor")
    public void sendInvitation(String email, String organizationName, String inviterName, String rawToken) {
        String link = "%s/invitations/accept?token=%s".formatted(properties.getBaseUrl(), encode(rawToken));
        send(email, "%s invited you to %s on SprintIQ".formatted(inviterName, organizationName),
                """
                %s has invited you to collaborate on %s.

                Accept the invitation:
                %s

                This invitation expires in %d days.
                """.formatted(inviterName, organizationName, link,
                        properties.getSecurity().getInvitationTtl().toDays()));
    }

    private void send(String to, String subject, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
        } catch (MailException ex) {
            log.error("Unable to send '{}' email to {}: {}", subject, to, ex.getMessage());
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
