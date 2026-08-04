package com.sprintiq.pms.modules.auth.web;

import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.modules.auth.application.AuthService;
import com.sprintiq.pms.modules.auth.web.dto.AuthDtos.*;
import com.sprintiq.pms.security.AppPrincipal;
import com.sprintiq.pms.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Authentication endpoints. Controllers here do nothing but bind, delegate and map —
 * all credential logic lives in {@link AuthService}.
 */
@Tag(name = "Authentication", description = "Registration, login, token lifecycle and password management")
@RestController
@RequestMapping(ApiConstants.API_V1 + "/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Register a new user and organization")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created"),
            @ApiResponse(responseCode = "409", description = "Email cannot be used"),
            @ApiResponse(responseCode = "422", description = "Validation failed")
    })
    @SecurityRequirements
    @PostMapping("/register")
    public ResponseEntity<AuthTokens> register(@Valid @RequestBody RegisterRequest request,
                                               HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request, httpRequest));
    }

    @Operation(summary = "Sign in with email and password")
    @SecurityRequirements
    @PostMapping("/login")
    public AuthTokens login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return authService.login(request, httpRequest);
    }

    @Operation(summary = "Exchange a refresh token for a new token pair")
    @SecurityRequirements
    @PostMapping("/refresh")
    public AuthTokens refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest httpRequest) {
        return authService.refresh(request.refreshToken(), httpRequest);
    }

    @Operation(summary = "Switch the active organization")
    @PostMapping("/switch-organization")
    public AuthTokens switchOrganization(@CurrentUser AppPrincipal principal,
                                         @Valid @RequestBody SwitchOrganizationRequest request,
                                         HttpServletRequest httpRequest) {
        return authService.switchOrganization(principal.userId(), request.organizationId(), httpRequest);
    }

    @Operation(summary = "Revoke the current session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/logout")
    public void logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
    }

    @Operation(summary = "Revoke every session for the current user")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/logout-all")
    public void logoutAll(@CurrentUser AppPrincipal principal) {
        authService.logoutAll(principal.userId());
    }

    @Operation(summary = "Request a password reset link",
            description = "Always returns 204 — the response never reveals whether the address is registered.")
    @SecurityRequirements
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/password/forgot")
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.requestPasswordReset(request.email());
    }

    @Operation(summary = "Complete a password reset")
    @SecurityRequirements
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/password/reset")
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
    }

    @Operation(summary = "Change the password of the signed-in user")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/password/change")
    public void changePassword(@CurrentUser AppPrincipal principal,
                               @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(principal.userId(), request);
    }

    @Operation(summary = "Verify an email address")
    @SecurityRequirements
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/email/verify")
    public void verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        authService.verifyEmail(request.token());
    }

    @Operation(summary = "Current principal, permissions and organization memberships")
    @GetMapping("/me")
    public AuthenticatedUser me(@CurrentUser AppPrincipal principal) {
        return authService.currentUser(principal.userId(), principal.organizationId());
    }
}
