package com.sp.securityprotocol.config;

import jakarta.annotation.PreDestroy;
import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.core.Utils;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.core.network.interceptors.MessageTracer;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.elements.config.Configuration;
import org.eclipse.californium.oscore.*;
import org.eclipse.californium.cose.AlgorithmID;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.*;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

@org.springframework.context.annotation.Configuration
public class CoapServerConfig {
    private static final Logger log = LoggerFactory.getLogger(CoapServerConfig.class);
    private CoapServer server;

    @Bean
    @ConfigurationProperties(prefix = "coap")
    public CoapProps coapProps() { return new CoapProps(); }

    @Bean
    public CoapServer coapServer(CoapProps props) throws OSException {
        // 1) 공용 Configuration
        Configuration cfg = Configuration.getStandard();
        final int port = props.getPort();

        // 2) 엔드포인트 빌더 (공용 cfg 사용)
        CoapEndpoint.Builder ep = new CoapEndpoint.Builder()
                .setConfiguration(cfg)
                .setInetSocketAddress(new InetSocketAddress(port));

        // 3) OSCORE: "static 등록"만 사용 (per-endpoint 팩토리 혼용 금지)
        if (props.getOscore().isEnabled()) {
            OSCoreCtxDB db = buildOscoreContext(props);
            OSCoreCoapStackFactory.useAsDefault(db);
//            ep.setCoapStackFactory(new OSCoreCoapStackFactory());

            log.info("CoAP OSCORE enabled (udp/{})", port);
            log.info("OSCORE cfg: sID={} rID={} salt?={} secretLen={}",
                    props.getOscore().getServerSenderIdHex(),
                    props.getOscore().getServerRecipientIdHex(),
                    props.getOscore().getMasterSalt()!=null,
                    props.getOscore().getMasterSecret()!=null
                            ? props.getOscore().getMasterSecret().replaceAll("\\s+","").length()/2 : 0);
        } else {
            log.info("CoAP plaintext (udp/{})", port);
        }

        // 4) 서버도 같은 cfg로 생성
        server = new CoapServer(cfg);

        // 5) 엔드포인트 "하나만" 추가 + Tracer만 달기
        CoapEndpoint endpoint = ep.build();
        endpoint.addInterceptor(new MessageTracer());
        server.addEndpoint(endpoint);

        // 6) 리소스 등록 (respond는 한 번만; MID/Token/ACK은 스택이 처리)
        server.add(new CoapResource("echo") {
            @Override
            public void handlePOST(CoapExchange exchange) {
                // (선택) 오래 걸리면 먼저 수락
                // exchange.accept();

                // 페이로드만 넘기면 Californium이 Type/MID/Token을 자동 세팅하고
                // OSCORE 레이어가 보호 응답으로 감쌉니다.
                exchange.respond(CoAP.ResponseCode.CONTENT, "hello");
            }


        });

        server.start();
        log.info("EP BOUND = {}", endpoint.getAddress());
        return server;
    }

    @PreDestroy
    public void stop() {
        if (server != null) {
            server.stop();
            server.destroy();
        }
    }

    private OSCoreCtxDB buildOscoreContext(CoapProps p) throws OSException {
        byte[] ms  = hex(p.getOscore().getMasterSecret());
        byte[] salt= p.getOscore().getMasterSalt()!=null ? hex(p.getOscore().getMasterSalt()) : null;
        byte[] sid = hex(p.getOscore().getServerSenderIdHex());   // 서버의 Sender ID
        byte[] rid = hex(p.getOscore().getServerRecipientIdHex());// 서버가 기대하는 Client의 Sender ID

        OSCoreCtx ctx = new OSCoreCtx(
                ms,
                false,                              // 서버 역할
                AlgorithmID.AES_CCM_16_64_128,      // AEAD
                sid,                                // sender_id (server)
                rid,                                // recipient_id (client)
                AlgorithmID.HKDF_HMAC_SHA_256,      // HKDF
                32,                                 // replay window
                salt,                               // master_salt
                null,                               // contextId
                0                                   // maxUnfragmentedSize (0=default)
        );
        HashMapCtxDB db = new HashMapCtxDB();
        db.addContext(ctx);
        return db;
    }

    private static byte[] hex(String s) {
        String t = s.replaceAll("\\s+","");
        ByteBuffer buf = ByteBuffer.allocate(t.length()/2);
        for (int i=0;i<t.length();i+=2)
            buf.put((byte)Integer.parseInt(t.substring(i,i+2),16));
        return buf.array();
    }

    // ---- properties holder ----
    public static class CoapProps {
        private int port = 5683;
        private Oscore oscore = new Oscore();
        public int getPort() { return port; } public void setPort(int p){ this.port=p; }
        public Oscore getOscore(){ return oscore; } public void setOscore(Oscore o){ this.oscore=o; }

        public static class Oscore {
            private boolean enabled = false;
            private String masterSecret;     // e.g. "0102030405060708090A0B0C0D0E0F10"
            private String masterSalt;       // e.g. "9E7CA92223786340"
            private String serverSenderIdHex = "02";
            private String serverRecipientIdHex = "01"; // ← 클라이언트 Sender ID와 반대로 설정
            public boolean isEnabled(){ return enabled; } public void setEnabled(boolean b){ enabled=b; }
            public String getMasterSecret(){ return masterSecret; } public void setMasterSecret(String s){ masterSecret=s; }
            public String getMasterSalt(){ return masterSalt; } public void setMasterSalt(String s){ masterSalt=s; }
            public String getServerSenderIdHex(){ return serverSenderIdHex; } public void setServerSenderIdHex(String s){ serverSenderIdHex=s; }
            public String getServerRecipientIdHex(){ return serverRecipientIdHex; } public void setServerRecipientIdHex(String s){ serverRecipientIdHex=s; }
        }
    }
}
