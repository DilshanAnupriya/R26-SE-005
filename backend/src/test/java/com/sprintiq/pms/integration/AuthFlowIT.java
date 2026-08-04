package com.sprintiq.pms.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sprintiq.pms.modules.auth.web.dto.AuthDtos.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class AuthFlowIT extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    @DisplayName("register issues a token pair that authenticates /auth/me")
    void registerThenCallProtectedEndpoint() throws Exception {
        RegisterRequest request = new RegisterRequest("founder@acme.test", "Str0ng!Passw0rd#2026",
                "Ada Founder", "Acme Robotics", "Europe/London");

        String responseBody = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.activeRole").value("OWNER"))
                .andReturn().getResponse().getContentAsString();

        AuthTokens tokens = objectMapper.readValue(responseBody, AuthTokens.class);
        assertThat(tokens.user().permissions()).contains("project:create", "sprint:manage");

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("founder@acme.test"))
                .andExpect(jsonPath("$.organizations", hasSizeGreaterThanZero()));
    }

    @Test
    @DisplayName("unauthenticated access to a protected endpoint returns the standard error shape")
    void protectedEndpointRejectsAnonymous() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("weak passwords are rejected with field-level detail")
    void weakPasswordIsRejected() throws Exception {
        RegisterRequest request = new RegisterRequest("weak@acme.test", "password",
                "Weak User", "Weak Org", "UTC");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));
    }

    private static org.hamcrest.Matcher<Iterable<?>> hasSizeGreaterThanZero() {
        return org.hamcrest.Matchers.iterableWithSize(org.hamcrest.Matchers.greaterThan(0));
    }
}
