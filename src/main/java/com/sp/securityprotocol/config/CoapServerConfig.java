package com.sp.securityprotocol.config;

import jakarta.annotation.PreDestroy;
import org.eclipse.californium.core.*;
import org.eclipse.californium.core.coap.*;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.eclipse.californium.oscore.*;
import org.eclipse.californium.cose.AlgorithmID;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.*;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

@Configuration
public class CoapServerConfig {
    private static final Logger log = LoggerFactory.getLogger(CoapServerConfig.class);
    private CoapServer server;

    @Bean @ConfigurationProperties(prefix = "coap")
    public CoapProps coapProps() { return new CoapProps(); }

    @Bean
    public CoapServer coapServer(CoapProps props) throws OSException {
        // 1) 서버/엔드포인트가 같은 Configuration을 반드시 공유
        org.eclipse.californium.elements.config.Configuration cfg =
                org.eclipse.californium.elements.config.Configuration.getStandard();

        final int port = props.getPort();

        // 2) OSCORE 팩토리 설정은 "한 방식"만 사용 (static or per-endpoint 중 하나만)
        CoapEndpoint.Builder ep = new CoapEndpoint.Builder()
                .setConfiguration(cfg)
                .setInetSocketAddress(new InetSocketAddress(port));

        if (props.getOscore().isEnabled()) {
            OSCoreCtxDB db = buildOscoreContext(props);

            // 옵션 A(권장): static 등록만 사용
            OSCoreCoapStackFactory.useAsDefault(db);

            // 옵션 B: per-endpoint 팩토리 사용 시엔 아래 한 줄만 쓰고 위 useAsDefault()는 빼세요.
            // ep.setCoapStackFactory(new OSCoreCoapStackFactory(db));

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

        // 3) 서버도 같은 cfg로 생성
        server = new CoapServer(cfg);

        // 4) 엔드포인트 1개만 추가
        CoapEndpoint endpoint = ep.build();
        server.addEndpoint(endpoint);

        // 5) 커스텀 인터셉터 "절대" 추가하지 말고, 관찰은 Tracer만
        endpoint.addInterceptor(new org.eclipse.californium.core.network.interceptors.MessageTracer());

        // 6) 리소스 등록 (respond는 한 번만, 이후 즉시 return)
        server.add(new CoapResource("echo") {
            @Override
            public void handlePOST(CoapExchange ex) {
                byte[] body = ex.getRequestPayload();
                Integer cf = ex.getRequestOptions().getContentFormat();
                int fmt = (cf != null) ? cf : MediaTypeRegistry.APPLICATION_OCTET_STREAM;

                if (ex.advanced().getRequest().isConfirmable()) {
                    ex.accept(); // 빈 ACK 먼저 (separate 강제)
                }

                java.util.concurrent.ScheduledExecutorService exec = (java.util.concurrent.ScheduledExecutorService) getExecutor(); // ✅ CoapResource 메서드
                if (exec == null) {                    // 이론상 handler 시점엔 거의 null 아님이 정상
                    ex.respond(CoAP.ResponseCode.CONTENT, (body != null) ? body : new byte[0], fmt);
                    return;
                }
                exec.execute(() -> {
                    try {
                        ex.respond(CoAP.ResponseCode.CONTENT, (body != null) ? body : new byte[0], fmt);
                    } catch (Throwable ignore) { /* 완료/타임아웃이면 무시 */ }
                });
            }


            @Override public void handlePUT(CoapExchange ex) { handlePOST(ex); }
            @Override public void handleGET(CoapExchange ex) { ex.respond("echo-get"); }
        });

        server.start();
        log.info("EP BOUND = {}", endpoint.getAddress());
        return server;
    }

    @PreDestroy
    public void stop() { if (server != null) { server.stop(); server.destroy(); } }

    private OSCoreCtxDB buildOscoreContext(CoapProps p) throws OSException {
        byte[] ms = hex(p.getOscore().getMasterSecret());
        byte[] salt = p.getOscore().getMasterSalt()!=null ? hex(p.getOscore().getMasterSalt()) : null;
        byte[] sid = hex(p.getOscore().getServerSenderIdHex());
        byte[] rid = hex(p.getOscore().getServerRecipientIdHex());
        OSCoreCtx ctx = new OSCoreCtx(
                ms, false, AlgorithmID.AES_CCM_16_64_128,
                sid, rid, AlgorithmID.HKDF_HMAC_SHA_256,
                32, salt, null, 0
        );
        HashMapCtxDB db = new HashMapCtxDB(); db.addContext(ctx); return db;
    }

    private static byte[] hex(String s) {
        String t = s.replaceAll("\\s+","");
        ByteBuffer buf = ByteBuffer.allocate(t.length()/2);
        for (int i=0;i<t.length();i+=2) buf.put((byte)Integer.parseInt(t.substring(i,i+2),16));
        return buf.array();
    }

    // ---- props holder ----
    public static class CoapProps {
        private int port = 5683;
        private Oscore oscore = new Oscore();
        public int getPort() { return port; } public void setPort(int p){ this.port=p; }
        public Oscore getOscore(){ return oscore; } public void setOscore(Oscore o){ this.oscore=o; }
        public static class Oscore {
            private boolean enabled=false;
            private String masterSecret; private String masterSalt;
            private String serverSenderIdHex="02", serverRecipientIdHex="01";
            public boolean isEnabled(){return enabled;} public void setEnabled(boolean b){enabled=b;}
            public String getMasterSecret(){return masterSecret;} public void setMasterSecret(String s){masterSecret=s;}
            public String getMasterSalt(){return masterSalt;} public void setMasterSalt(String s){masterSalt=s;}
            public String getServerSenderIdHex(){return serverSenderIdHex;}
            public void setServerSenderIdHex(String s){serverSenderIdHex=s;}
            public String getServerRecipientIdHex(){return serverRecipientIdHex;}
            public void setServerRecipientIdHex(String s){serverRecipientIdHex=s;}
        }
    }
    }
