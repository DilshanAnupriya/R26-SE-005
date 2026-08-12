package com.sprintiq.pms.config;

import com.sprintiq.pms.security.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Realtime board and notification updates. Topics are tenant-scoped
 * ({@code /topic/org.{orgId}.project.{projectId}}) and the STOMP CONNECT frame
 * carries the same bearer token used by the REST API.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final AppProperties properties;
    private final WebSocketAuthInterceptor authInterceptor;

    /**
     * The broker sits outside the servlet filter chain, so authentication and tenant
     * scoping have to be applied here rather than inherited from HTTP security.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOrigins(properties.getCors().getAllowedOrigins().toArray(String[]::new))
                .withSockJS();
    }
}
