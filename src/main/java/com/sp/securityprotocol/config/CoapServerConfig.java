package com.sp.securityprotocol.config;

import jakarta.annotation.PreDestroy;
import org.eclipse.californium.core.*;
import org.eclipse.californium.core.coap.*;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.core.network.interceptors.MessageTracer;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.eclipse.californium.oscore.*;
import org.eclipse.californium.cose.AlgorithmID;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.*;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.core.Utils;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.oscore.HashMapCtxDB;
import org.eclipse.californium.oscore.OSCoreCoapStackFactory;
import org.eclipse.californium.oscore.OSCoreCtx;
import org.eclipse.californium.oscore.OSCoreCtxDB;
import org.eclipse.californium.oscore.OSException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;



@Configuration
public class CoapServerConfig {
    private static final Logger log = LoggerFactory.getLogger(CoapServerConfig.class);

    private CoapServer server;
    private ScheduledExecutorService mainExec;
    private ScheduledExecutorService secExec;

    @Bean
    @ConfigurationProperties(prefix = "coap")
    public CoapProps coapProps() {
        return new CoapProps();
    }

    @Bean
    public CoapServer coapServer(CoapProps props) throws OSException {
        // 1) 서버/엔드포인트가 같은 Configuration을 반드시 공유
        org.eclipse.californium.elements.config.Configuration cfg =
                org.eclipse.californium.elements.config.Configuration.getStandard();

        final int port = props.getPort();

        // 2) CoapEndpoint Builder (단일 엔드포인트)
        CoapEndpoint.Builder ep = new CoapEndpoint.Builder()
                .setConfiguration(cfg)
                .setInetSocketAddress(new InetSocketAddress(port));

        // 3) OSCORE 팩토리 설정은 "한 방식"만 사용 (static 등록만)
        if (props.getOscore().isEnabled()) {
            OSCoreCtxDB db = buildOscoreContext(props);
            OSCoreCoapStackFactory.useAsDefault(db);     // ✅ static 경로만 사용
            log.info("CoAP OSCORE enabled (udp/{})", port);
            log.info("OSCORE cfg: sID={} rID={} salt?={} secretLen={}",
                    props.getOscore().getServerSenderIdHex(),
                    props.getOscore().getServerRecipientIdHex(),
                    props.getOscore().getMasterSalt() != null,
                    props.getOscore().getMasterSecret() != null
                            ? props.getOscore().getMasterSecret().replaceAll("\\s+", "").length() / 2 : 0);
        } else {
            log.info("CoAP plaintext (udp/{})", port);
        }

        // 4) 서버 생성 + executor 주입 (서버/리소스가 동일 executor 공유)
        server = new CoapServer(cfg);

        mainExec = Executors.newScheduledThreadPool(2);
        secExec  = Executors.newSingleThreadScheduledExecutor();
        server.setExecutors(mainExec, secExec, /*start*/ false);

        // 5) 엔드포인트 등록 (1개만)
        CoapEndpoint endpoint = ep.build();
        server.addEndpoint(endpoint);
        endpoint.addInterceptor(new MessageTracer());

        // 6) 리소스 등록 (separate 응답은 반드시 리소스 executor에서 수행)
        CoapResource echo = new CoapResource("echo") {
            @Override
            public void handlePOST(CoapExchange ex) {
                byte[] body = ex.getRequestPayload();
                Integer cf = ex.getRequestOptions().getContentFormat();
                int fmt = (cf != null) ? cf : MediaTypeRegistry.APPLICATION_OCTET_STREAM;

                final Request req = ex.advanced().getRequest();
                String tokHex = (req.getToken() == null) ? "-" : Utils.toHexString(req.getToken().getBytes());
                log.info("echo: RX mid={}, tok={}", req.getMID(), tokHex);

                // 요청이 CON이면 빈 ACK 먼저
                if (req.isConfirmable()) {
                    ex.accept();
                    log.info("echo: sent Empty ACK for mid={}", req.getMID());
                }

                // 반드시 리소스 executor에서 separate 응답
                ScheduledExecutorService exec = (ScheduledExecutorService) getExecutor();
                if (exec == null) {
                    // 매우 드문 케이스: 방어적으로 inline 처리
                    log.warn("echo: getExecutor()==null, responding inline (still separate after accept)");
                    try {
                        ex.respond(org.eclipse.californium.core.coap.CoAP.ResponseCode.CONTENT,
                                (body != null) ? body : new byte[0], fmt);
                        log.info("echo: respond() inline returned");
                    } catch (Throwable t) {
                        log.error("echo: respond() inline FAILED", t);
                    }
                    return;
                }

                // 1틱 지연 후 separate 응답
                exec.schedule(() -> {
                    try {
                        log.info("echo: about to respond (separate) for mid={}, tok={}", req.getMID(), tokHex);
                        ex.respond(org.eclipse.californium.core.coap.CoAP.ResponseCode.CONTENT,
                                (body != null) ? body : new byte[0], fmt);
                        log.info("echo: respond() returned OK");
                    } catch (Throwable t) {
                        log.error("echo: respond() FAILED", t);
                    }
                }, 1, TimeUnit.MILLISECONDS);
            }

            @Override
            public void handlePUT(CoapExchange ex) { handlePOST(ex); }

            @Override
            public void handleGET(CoapExchange ex) { ex.respond("echo-get"); }
        };

        // 리소스에도 같은 executor를 명시 주입
//        echo.setExecutor(mainExec);
        server.add(echo);

        // 7) 시작
        server.start();
        log.info("EP BOUND = {}", endpoint.getAddress());
        return server;
    }

    @PreDestroy
    public void stop() {
        if (server != null) {
            try { server.stop(); } catch (Throwable ignore) {}
            try { server.destroy(); } catch (Throwable ignore) {}
        }
        if (mainExec != null) {
            try { mainExec.shutdownNow(); } catch (Throwable ignore) {}
        }
        if (secExec != null) {
            try { secExec.shutdownNow(); } catch (Throwable ignore) {}
        }
    }

    private OSCoreCtxDB buildOscoreContext(CoapProps p) throws OSException {
        byte[] ms   = hex(p.getOscore().getMasterSecret());
        byte[] salt = (p.getOscore().getMasterSalt() != null) ? hex(p.getOscore().getMasterSalt()) : null;
        byte[] sid  = hex(p.getOscore().getServerSenderIdHex());
        byte[] rid  = hex(p.getOscore().getServerRecipientIdHex());

        OSCoreCtx ctx = new OSCoreCtx(
                ms,                                 // master secret
                false,                              // 서버 역할
                AlgorithmID.AES_CCM_16_64_128,      // AEAD
                sid,                                // sender_id
                rid,                                // recipient_id
                AlgorithmID.HKDF_HMAC_SHA_256,      // HKDF
                32,                                 // replay window
                salt,                               // master_salt (nullable)
                null,                               // contextId (nullable)
                0                                   // maxUnfragmentedSize (0=default)
        );

        HashMapCtxDB db = new HashMapCtxDB();
        db.addContext(ctx);
        return db;
    }

    private static byte[] hex(String s) {
        String t = s.replaceAll("\\s+", "");
        ByteBuffer buf = ByteBuffer.allocate(t.length() / 2);
        for (int i = 0; i < t.length(); i += 2) {
            buf.put((byte) Integer.parseInt(t.substring(i, i + 2), 16));
        }
        return buf.array();
    }

    // ---- props holder ----
    public static class CoapProps {
        private int port = 5683;
        private Oscore oscore = new Oscore();

        public int getPort() { return port; }
        public void setPort(int p) { this.port = p; }

        public Oscore getOscore() { return oscore; }
        public void setOscore(Oscore o) { this.oscore = o; }

        public static class Oscore {
            private boolean enabled = false;
            private String masterSecret;
            private String masterSalt;
            private String serverSenderIdHex = "02";
            private String serverRecipientIdHex = "01";

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean b) { enabled = b; }

            public String getMasterSecret() { return masterSecret; }
            public void setMasterSecret(String s) { masterSecret = s; }

            public String getMasterSalt() { return masterSalt; }
            public void setMasterSalt(String s) { masterSalt = s; }

            public String getServerSenderIdHex() { return serverSenderIdHex; }
            public void setServerSenderIdHex(String s) { serverSenderIdHex = s; }

            public String getServerRecipientIdHex() { return serverRecipientIdHex; }
            public void setServerRecipientIdHex(String s) { serverRecipientIdHex = s; }
        }
    }
}