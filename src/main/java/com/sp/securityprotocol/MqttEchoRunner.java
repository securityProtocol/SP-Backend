package com.sp.securityprotocol;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class MqttEchoRunner implements CommandLineRunner, MqttCallbackExtended {

    private final MqttProperties props;
    private MqttClient client;
    private final AtomicBoolean subscribed = new AtomicBoolean(false);

    // ★ 생성자 주입 (스프링이 MqttProperties를 자동 주입)
    public MqttEchoRunner(MqttProperties props) {
        this.props = props;
    }

    @Override
    public void run(String... args) throws Exception {
        final String uri = props.useWebsocket()
                ? "wss://" + props.host() + ":" + props.port() + "/mqtt"
                : "ssl://" + props.host() + ":" + props.port();

        client = new MqttClient(uri, props.clientId(), new MemoryPersistence());
        client.setCallback(this);
        System.out.println("[MQTT] connecting to " + uri + " as clientId=" + props.clientId());
        System.out.println("[MQTT] requestTopic=" + props.requestTopic() + " responseTopic=" + props.responseTopic());
        MqttConnectOptions opt = new MqttConnectOptions();
        opt.setCleanSession(true);
        opt.setAutomaticReconnect(true);
        opt.setConnectionTimeout(10);
        opt.setKeepAliveInterval(30);

        if (props.username() != null && !props.username().isBlank()) {
            opt.setUserName(props.username());
        }
        if (props.password() != null) {
            opt.setPassword(props.password().toCharArray());
        }

        client.connect(opt); // 연결 성공 시 connectComplete에서 subscribe
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
//        if (subscribed.compareAndSet(false, true)) {
//            try {
//                client.subscribe(props.requestTopic(), props.qos());
//                System.out.println("[MQTT] subscribed: " + props.requestTopic());
//            } catch (MqttException e) {
//                subscribed.set(false);
//                System.err.println("[MQTT] subscribe failed: " + e.getMessage());
//            }
//        }
        System.out.println("[MQTT] connected " + (reconnect ? "(reconnect) " : "") + "to " + serverURI);
        try {
            client.subscribe("lab/#", props.qos());  // ← 임시 스니프
            System.out.println("[MQTT] subscribed: lab/# (sniff)");
        } catch (MqttException e) {
            System.err.println("[MQTT] subscribe failed: " + e.getMessage());
        }
    }

    @Override public void connectionLost(Throwable cause) {
        subscribed.set(false);
        System.out.println("[MQTT] connection lost: " + (cause != null ? cause.getMessage() : "unknown"));
    }

    @Override public void messageArrived(String topic, MqttMessage in) {
        System.out.println("[MQTT] arrived topic=" + topic + " payload=" + new String(in.getPayload()));
        if (topic.equals(props.requestTopic())) {
            try {
                MqttMessage out = new MqttMessage(in.getPayload());
                out.setQos(props.qos());
                out.setRetained(false);
                client.publish(props.responseTopic(), out);
            } catch (Exception e) {
                System.err.println("[MQTT] publish error: " + e.getMessage());
            }
        }
    }

    @Override public void deliveryComplete(IMqttDeliveryToken token) { }
}