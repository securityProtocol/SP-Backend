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
import org.eclipse.californium.core.network.interceptors.MessageInterceptor;

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
        int port = props.getPort();
        CoapEndpoint.Builder ep = new CoapEndpoint.Builder()
                .setInetSocketAddress(new InetSocketAddress(port));

        if (props.getOscore().isEnabled()) {
            OSCoreCtxDB db = buildOscoreContext(props);
            OSCoreCoapStackFactory.useAsDefault(db);
            ep.setCoapStackFactory(new OSCoreCoapStackFactory());
            log.info("CoAP OSCORE enabled (udp/{})", port);
            // OSCORE 분기 내부에 추가 (부팅 시 1회 로깅)
            log.info("OSCORE cfg: sID={} rID={} salt?={} secretLen={}",
                    props.getOscore().getServerSenderIdHex(),
                    props.getOscore().getServerRecipientIdHex(),
                    props.getOscore().getMasterSalt()!=null,
                    props.getOscore().getMasterSecret()!=null
                            ? props.getOscore().getMasterSecret().replaceAll("\\s+","").length()/2 : 0);

        } else {
            log.info("CoAP plaintext (udp/{})", port);
        }

        server = new CoapServer();
        CoapEndpoint endpoint = ep.build();
        endpoint.addInterceptor(new MessageInterceptor() {
            @Override
            public void sendRequest(Request request) {

            }

            @Override
            public void sendResponse(Response r) {
                log.info("SERIALIZE sendResponse type={}, mid={}, token={}",
                        r.getType(), r.getMID(), r.getTokenString());
            }

            @Override
            public void sendEmptyMessage(EmptyMessage message) {

            }

            @Override
            public void receiveRequest(Request r) {
                log.info("RX  req type={}, mid={}, token={}", r.getType(), r.getMID(), r.getTokenString());

            }

            @Override
            public void receiveResponse(Response response) {

            }

            @Override
            public void receiveEmptyMessage(EmptyMessage message) {

            }
        });
        server.addEndpoint(endpoint);

        server.getEndpoints().forEach(e -> log.info("EP BOUND = {}", e.getAddress())); // ★ 추가

        server.add(new CoapResource("echo") {
            @Override
            public void handlePOST(CoapExchange ex) {
                byte[] body = ex.getRequestPayload();
                // 요청의 Content-Format을 그대로 돌려주기 (없으면 OCTET_STREAM)
                Integer cf = ex.getRequestOptions().getContentFormat();
                int contentFormat = (cf != null) ? cf : MediaTypeRegistry.APPLICATION_OCTET_STREAM;

                // 메타(Type/MID/Token)는 손대지 말고, 평범한 respond 사용
                ex.respond(CoAP.ResponseCode.CONTENT,
                        (body != null) ? body : new byte[0],
                        contentFormat);
            }

            @Override public void handlePUT(CoapExchange ex) { handlePOST(ex); }
            @Override public void handleGET(CoapExchange ex) { ex.respond("echo-get"); }
        });

//
//        server.add(new CoapResource("echo") {
//            @Override public void handlePOST(CoapExchange ex) {
//                log.info("POST /echo!!!!!");
//                byte[] in = ex.getRequestPayload();
//                byte[] out = (in == null ? new byte[0] : in);
//                Integer cf = ex.getRequestOptions().getContentFormat();
//                if (cf != null) {
//                    ex.respond(CoAP.ResponseCode.CONTENT, out, cf); // ✅ content-format 지정 오버로드
//                } else {
//                    ex.respond(CoAP.ResponseCode.CONTENT, out);
//                }
//            }
//            @Override public void handlePUT(CoapExchange ex) { handlePOST(ex); }
//            @Override public void handleGET(CoapExchange ex) { ex.respond("echo-get"); }
//        });

        server.start();
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
                ms,
                false,                              // 서버 역할
                AlgorithmID.AES_CCM_16_64_128,      // AEAD
                sid,                                // sender_id
                rid,                                // recipient_id
                AlgorithmID.HKDF_HMAC_SHA_256,      // HKDF
                32,                                 // replay_size
                salt,                               // master_salt
                null,                               // contextId (없음)
                0                                   // maxUnfragmentedSize (0=기본)
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
