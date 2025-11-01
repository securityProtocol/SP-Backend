package com.sp.securityprotocol;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(MqttProperties.class)  // ← 여기 추가!
public class SecurityProtocolApplication {

    public static void main(String[] args) {
        SpringApplication.run(SecurityProtocolApplication.class, args);
    }

}
