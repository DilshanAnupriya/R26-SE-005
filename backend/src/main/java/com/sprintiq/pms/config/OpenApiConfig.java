package com.sprintiq.pms.config;

import com.sprintiq.pms.common.web.ApiConstants;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI sprintIqOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SprintIQ API")
                        .version("v1")
                        .description("""
                                AI-powered Agile Project Management SaaS.

                                **Tenancy** — every request is scoped to one organization, taken from the
                                `org` claim of the access token. Users belonging to several organizations
                                switch context by re-issuing a token for the target organization.

                                **Errors** — all failures share the `ApiError` shape.

                                **AI** — every prediction returns a value, uncertainty bounds, contributing
                                drivers and a plain-language explanation, and can be accepted or overridden.
                                """)
                        .contact(new Contact().name("SprintIQ Platform Team").email("platform@sprintiq.example"))
                        .license(new License().name("Proprietary")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local"),
                        new Server().url("https://api.sprintiq.example").description("Production")))
                .components(new Components().addSecuritySchemes(ApiConstants.SECURITY_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Access token obtained from POST /api/v1/auth/login")))
                .addSecurityItem(new SecurityRequirement().addList(ApiConstants.SECURITY_SCHEME));
    }
}
