//package com.sp.securityprotocol.config;
//
//import jakarta.annotation.PreDestroy;
//import org.eclipse.californium.core.CoapResource;
//import org.eclipse.californium.core.CoapServer;
//import org.eclipse.californium.core.coap.CoAP;
//import org.eclipse.californium.core.coap.MediaTypeRegistry;
//import org.eclipse.californium.core.network.CoapEndpoint;
//import org.eclipse.californium.core.network.interceptors.MessageTracer;
//import org.eclipse.californium.core.server.resources.CoapExchange;
//import org.eclipse.californium.elements.config.CertificateAuthenticationMode;
//import org.eclipse.californium.elements.config.Configuration;
//import org.eclipse.californium.elements.util.NamedThreadFactory;
//import org.eclipse.californium.scandium.DTLSConnector;
//import org.eclipse.californium.scandium.config.DtlsConfig;
//import org.eclipse.californium.scandium.config.DtlsConnectorConfig;
//import org.eclipse.californium.scandium.dtls.cipher.CipherSuite;
//import org.eclipse.californium.scandium.dtls.x509.SingleCertificateProvider;
//import org.eclipse.californium.scandium.dtls.x509.StaticNewAdvancedCertificateVerifier;
//import org.springframework.boot.SpringApplication;
//import org.springframework.boot.context.properties.ConfigurationProperties;
//import org.springframework.context.annotation.Bean;
//import org.eclipse.californium.core.coap.*;
//
//import org.eclipse.californium.oscore.*;
//import org.slf4j.Logger; import org.slf4j.LoggerFactory;
//import org.springframework.context.annotation.*;
//
//import java.io.InputStream;
//import java.net.InetSocketAddress;
//import javax.net.ssl.KeyManagerFactory;
//import java.security.KeyStore;
//import java.security.cert.X509Certificate;
//import java.util.Arrays;
//import java.util.Collections;
//import java.util.List;
//import java.util.stream.Collectors;
//
//@org.springframework.context.annotation.Configuration
//public class CoapDTLSConfig {
//    private static final Logger log = LoggerFactory.getLogger(CoapDTLSConfig.class);
//    private CoapServer server;
//
//    public static void main(String[] args) {
//        SpringApplication.run(CoapDTLSConfig.class, args);
//    }
//
//    @Bean
//    @ConfigurationProperties(prefix = "coap.dtls")
//    public DtlsProps dtlsProps() { return new DtlsProps(); }
//
//    @Bean(destroyMethod = "stop")
//    public CoapServer coapsServer(DtlsProps p) throws Exception {
//        if (!p.isEnabled()) {
//            log.info("CoAP+DTLS disabled.");
//            return null;
//        }
//
//        Configuration cfg = Configuration.createStandardWithoutFile();
//        server = new CoapServer(cfg);
//
//        DTLSConnector dtls = buildDtlsConnector(p, cfg);
//        CoapEndpoint ep = new CoapEndpoint.Builder()
//                .setConfiguration(cfg)
//                .setConnector(dtls)
//                .build();
//        ep.addInterceptor(new MessageTracer());
//        server.addEndpoint(ep);
//
//        // 테스트 리소스
//        server.add(new CoapResource("echo") {
//            @Override public void handlePOST(CoapExchange ex) {
//                byte[] pl = ex.getRequestPayload();
//                log.info("ECHO payload: {}", new String(pl, java.nio.charset.StandardCharsets.UTF_8));
//                int cf = ex.getRequestOptions().getContentFormat();
//                Response r = new Response(CoAP.ResponseCode.CONTENT);
//                r.setPayload(pl);
//                if (cf != MediaTypeRegistry.UNDEFINED) r.getOptions().setContentFormat(cf);
//                ex.respond(r);
//            }
//            @Override public void handleGET(CoapExchange ex) {
//                ex.respond(CoAP.ResponseCode.CONTENT, "ok".getBytes());
//            }
//        });
//
//        server.start();
//        log.info("CoAP+DTLS listening udp/{}", p.getPort());
//        return server;
//    }
//
//    @PreDestroy
//    public void stop() {
//        if (server != null) {
//            server.stop();
//            server.destroy();
//        }
//    }
//
//    /* ========= DTLS only ========= */
//
//    private DTLSConnector buildDtlsConnector(DtlsProps d, Configuration cfg) throws Exception {
//        // 서버 인증서(p12) 로드 (HTTPS와 동일 인증서 변환본 사용 가능)
//        KeyStore ks = KeyStore.getInstance("PKCS12");
//        try (InputStream in = resource(d.getKeystore())) {
//            if (in == null) throw new IllegalArgumentException("keystore not found: " + d.getKeystore());
//            ks.load(in, d.getStorePass().toCharArray());
//        }
//
//        boolean isEc = isEcCertificate(ks);
//
//        DtlsConnectorConfig.Builder b = DtlsConnectorConfig.builder(cfg)
//                .setAddress(new InetSocketAddress(d.getPort()))
//                .set(DtlsConfig.DTLS_RECOMMENDED_CIPHER_SUITES_ONLY, false)
//                .set(DtlsConfig.DTLS_MAX_TRANSMISSION_UNIT, d.getMtu())
//                .set(DtlsConfig.DTLS_CLIENT_AUTHENTICATION_MODE, CertificateAuthenticationMode.NONE);
//
//        // 여러 스위트를 한 번에 광고
//        b.set(DtlsConfig.DTLS_CIPHER_SUITES,
//                Arrays.asList(cipherSuites(d.getCiphers(), isEc)));
//
//        // 서버 인증서/개인키 주입 (SingleCertificateProvider)
//        String alias = Collections.list(ks.aliases()).stream()
//                .filter(a -> { try { return ks.isKeyEntry(a); } catch (Exception e) { return false; } })
//                .findFirst().orElseThrow(() -> new IllegalStateException("no key entry in keystore"));
//        java.security.PrivateKey pk =
//                (java.security.PrivateKey) ks.getKey(alias, d.getKeyPass().toCharArray());
//        X509Certificate[] chain = Arrays.copyOf(
//                ks.getCertificateChain(alias),
//                ks.getCertificateChain(alias).length,
//                X509Certificate[].class);
//        b.setCertificateIdentityProvider(new SingleCertificateProvider(pk, chain));
//
//        return new DTLSConnector(b.build());
//    }
//
//    private static boolean isEcCertificate(KeyStore ks) throws Exception {
//        String alias = Collections.list(ks.aliases())
//                .stream().filter(a -> { try { return ks.isKeyEntry(a); } catch (Exception e) { return false; } })
//                .findFirst().orElse(null);
//        if (alias == null) throw new IllegalStateException("no key entry in keystore");
//        X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
//        if (cert == null) throw new IllegalStateException("no certificate for alias: " + alias);
//        return "EC".equalsIgnoreCase(cert.getPublicKey().getAlgorithm()); // true=ECDSA, false=RSA
//    }
//
//    private CipherSuite[] cipherSuites(java.util.List<String> keys, boolean isEc) {
//        java.util.List<String> wants =
//                (keys == null || keys.isEmpty()) ? java.util.Arrays.asList("aesgcm") : keys;
//
//        java.util.List<CipherSuite> all = CipherSuite.getCertificateCipherSuites(
//                false,
//                java.util.Collections.singletonList(
//                        isEc ? CipherSuite.CertificateKeyAlgorithm.EC : CipherSuite.CertificateKeyAlgorithm.RSA));
//
//        java.util.List<CipherSuite> out = new java.util.ArrayList<>();
//        for (String k : wants) {
//            String t = k.toLowerCase();
//            java.util.function.Predicate<CipherSuite> f =
//                    "aes256gcm".equals(t) ? cs -> cs.name().contains("AES_256_GCM") :
//                            "chacha20".equals(t) ? cs -> cs.name().contains("CHACHA20") :
//                                    cs -> cs.name().contains("AES_128_GCM"); // aesgcm
//            List<CipherSuite> finalOut = out;
//            all.stream().filter(f).forEach(cs -> { if (!finalOut.contains(cs)) finalOut.add(cs); });
//        }
//        if (out.isEmpty()) out = all; // 폴백
//        return out.toArray(new CipherSuite[0]);
//    }
//
//    private InputStream resource(String location) throws Exception {
//        if (location == null) return null;
//
//        // classpath:
//        if (location.startsWith("classpath:")) {
//            String path = location.substring("classpath:".length());
//            InputStream in = getClass().getResourceAsStream(path.startsWith("/") ? path : "/" + path);
//            if (in == null) throw new java.io.FileNotFoundException("classpath resource not found: " + path);
//            return in;
//        }
//
//        // file: 또는 일반 파일 경로
//        if (location.startsWith("file:")) {
//            // 안전하게 URI로 파싱 후 파일 열기
//            java.net.URI uri = java.net.URI.create(location);
//            return java.nio.file.Files.newInputStream(java.nio.file.Paths.get(uri));
//        } else {
//            // 순수 경로
//            return new java.io.FileInputStream(location);
//        }
//    }
//
//
//    /* ========= Props ========= */
//
//    public static class DtlsProps {
//        private boolean enabled = true;
//        private int     port = 9001;           // UDP 9001
//        private String  keystore;              // e.g. classpath:server.p12
//        private String  storePass;
//        private String  keyPass;
//        private java.util.List<String> ciphers = java.util.Arrays.asList("aesgcm"); // ← 변경
//        private int     mtu = 1200;
//
//        public boolean isEnabled(){ return enabled; } public void setEnabled(boolean b){ enabled = b; }
//        public int getPort(){ return port; } public void setPort(int p){ port = p; }
//        public String getKeystore(){ return keystore; } public void setKeystore(String s){ keystore = s; }
//        public String getStorePass(){ return storePass; } public void setStorePass(String s){ storePass = s; }
//        public String getKeyPass(){ return keyPass; } public void setKeyPass(String s){ keyPass = s; }
//        public java.util.List<String> getCiphers(){ return ciphers; }        public void setCiphers(java.util.List<String> v){ this.ciphers = v; }
//        public int getMtu(){ return mtu; } public void setMtu(int m){ mtu = m; }
//    }
//}
