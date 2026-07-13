package com.bovae.yac.config;

import com.bovae.yac.ws.StompAuthChannelInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.invocation.HandlerMethodArgumentResolver;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.SpringAuthorizationEventPublisher;
import org.springframework.security.messaging.access.intercept.AuthorizationChannelInterceptor;
import org.springframework.security.messaging.context.AuthenticationPrincipalArgumentResolver;
import org.springframework.security.messaging.context.SecurityContextChannelInterceptor;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;

/**
 * Manual WebSocket security config — replaces @EnableWebSocketSecurity to disable
 * STOMP-level CSRF. SockJS transport cannot reliably forward CSRF tokens in CONNECT frames.
 * HTTP-level CSRF for /ws/** is already disabled in SecurityConfig.
 */
@Configuration
@RequiredArgsConstructor
public class WebSocketSecurityConfig implements WebSocketMessageBrokerConfigurer {

    private final ApplicationContext applicationContext;
    private final AuthorizationManager<Message<?>> messageAuthorizationManager;
    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> argumentResolvers) {
        argumentResolvers.add(new AuthenticationPrincipalArgumentResolver());
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        var authz = new AuthorizationChannelInterceptor(messageAuthorizationManager);
        authz.setAuthorizationEventPublisher(new SpringAuthorizationEventPublisher(applicationContext));
        // Membership/presence/CONNECT checks run after the security context is populated.
        registration.interceptors(new SecurityContextChannelInterceptor(), authz, stompAuthChannelInterceptor);
    }
}
