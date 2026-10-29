package com.chat.backend.realtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler handler;
    private final String[] allowedOrigins;

    public WebSocketConfig(ChatWebSocketHandler handler,
                           @Value("${app.allowed-origins:http://localhost:5173,http://localhost:8080}") String[] allowedOrigins) {
        this.handler = handler;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // WebSockets are not covered by CORS, so a malicious page could open one using the
        // victim's session cookie. The explicit Origin allowlist is what stops that.
        registry.addHandler(handler, "/ws")
                .setAllowedOriginPatterns(allowedOrigins);
    }

    @Bean
    ServletServerContainerFactoryBean webSocketContainer() {
        var c = new ServletServerContainerFactoryBean();
        // Bounds memory per frame; the app-level limit is 4000 chars.
        c.setMaxTextMessageBufferSize(16 * 1024); // // todo explain
        return c;
    }
}
