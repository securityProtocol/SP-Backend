package com.sp.securityprotocol;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.mqtt")
public record MqttProperties(
        String host,
        int port,
        String clientId,
        String username,
        String password,
        String requestTopic,
        String responseTopic,
        int qos,
        boolean useWebsocket
) {}