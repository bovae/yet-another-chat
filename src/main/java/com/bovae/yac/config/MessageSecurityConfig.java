package com.bovae.yac.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;

@Configuration
public class MessageSecurityConfig {

    @Bean
    AuthorizationManager<Message<?>> messageAuthorizationManager() {
        return MessageMatcherDelegatingAuthorizationManager.builder()
                .simpTypeMatchers(
                        SimpMessageType.CONNECT,
                        SimpMessageType.DISCONNECT,
                        SimpMessageType.UNSUBSCRIBE,
                        SimpMessageType.HEARTBEAT)
                .permitAll()
                .simpDestMatchers("/app/**")
                .authenticated()
                .simpSubscribeDestMatchers("/topic/**", "/queue/**", "/user/**")
                .authenticated()
                .anyMessage()
                .denyAll()
                .build();
    }
}
