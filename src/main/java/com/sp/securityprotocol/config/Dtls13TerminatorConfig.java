//package com.sp.securityprotocol.config;
//
//import jakarta.annotation.PostConstruct;
//import jakarta.annotation.PreDestroy;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.boot.context.properties.ConfigurationProperties;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//
//import javax.net.ssl.KeyManager;
//import javax.net.ssl.KeyManagerFactory;
//import javax.net.ssl.SSLContext;
//import javax.net.ssl.SSLParameters;
//import javax.net.ssl.SSLEngine;
//import javax.net.ssl.SSLEngineResult;
//import javax.net.ssl.TrustManager;
//import javax.net.ssl.TrustManagerFactory;
//import java.io.FileInputStream;
//import java.io.IOException;
//import java.net.*;
//import java.nio.ByteBuffer;
//import java.nio.channels.*;
//import java.security.*;
//import java.security.cert.CertificateException;
//import java.time.Duration;
//import java.util.*;
//import java.util.concurrent.*;
//
//@Configuration
//public class Dtls13TerminatorConfig {
//
//    @Bean
//    @ConfigurationProperties(prefix = "coap.dtls13")
//    public D13Props dtls13Props() { return new D13Props(); }
//
//    @Bean(initMethod = "start", destroyMethod = "stop")
//    public Dtls13Terminator dtls13Terminator(D13Props p) { return new Dtls13Terminator(p); }
//
//    /** 설정 값 */
//    public static class D13Props {
//        private boolean enabled = true;
//        private int listenPort = 9001;            // 외부 DTLS(UDP) 수신 포트
//        private String provider = "wolfJSSE";     // 선호 Provider 이름(없어도 동작)
//        private String protocol = "DTLSv1.3";     // DTLS 1.3 강제
//        private List<String> ciphers = Arrays.asList(
//                "TLS_AES_128_GCM_SHA256",
//                "TLS_AES_256_GCM_SHA384",
//                "TLS_CHACHA20_POLY1305_SHA256"
//        );
//        private String p12;                       // 서버 인증서(PKCS#12) 경로
//        private String p12Pass;
//        private boolean needClientAuth = false;   // mTLS 필요 시 true
//        private int mtu = 1200;
//
//        // 내부 평문 CoAP 백엔드(프록시 대상)
//        private String upstreamHost = "127.0.0.1";
//        private int upstreamPort = 5683;
//        private int upstreamTimeoutMs = 2000;
//
//        public boolean isEnabled() { return enabled; }
//        public void setEnabled(boolean enabled) { this.enabled = enabled; }
//        public int getListenPort() { return listenPort; }
//        public void setListenPort(int listenPort) { this.listenPort = listenPort; }
//        public String getProvider() { return provider; }
//        public void setProvider(String provider) { this.provider = provider; }
//        public String getProtocol() { return protocol; }
//        public void setProtocol(String protocol) { this.protocol = protocol; }
//        public List<String> getCiphers() { return ciphers; }
//        public void setCiphers(List<String> ciphers) { this.ciphers = ciphers; }
//        public String getP12() { return p12; }
//        public void setP12(String p12) { this.p12 = p12; }
//        public String getP12Pass() { return p12Pass; }
//        public void setP12Pass(String p12Pass) { this.p12Pass = p12Pass; }
//        public boolean isNeedClientAuth() { return needClientAuth; }
//        public void setNeedClientAuth(boolean needClientAuth) { this.needClientAuth = needClientAuth; }
//        public int getMtu() { return mtu; }
//        public void setMtu(int mtu) { this.mtu = mtu; }
//        public String getUpstreamHost() { return upstreamHost; }
//        public void setUpstreamHost(String upstreamHost) { this.upstreamHost = upstreamHost; }
//        public int getUpstreamPort() { return upstreamPort; }
//        public void setUpstreamPort(int upstreamPort) { this.upstreamPort = upstreamPort; }
//        public int getUpstreamTimeoutMs() { return upstreamTimeoutMs; }
//        public void setUpstreamTimeoutMs(int upstreamTimeoutMs) { this.upstreamTimeoutMs = upstreamTimeoutMs; }
//    }
//
//    /** DTLS 1.3 Terminator (외부 DTLS ⇄ 내부 평문 CoAP) */
//    public static class Dtls13Terminator {
//        private static final Logger log = LoggerFactory.getLogger(Dtls13Terminator.class);
//
//        private final D13Props p;
//        private volatile boolean running = false;
//
//        private Selector selector;
//        private DatagramChannel ch;
//        private ExecutorService ioPool;
//        private SSLContext sslCtx;
//        private final Map<SocketAddress, Session> sessions = new ConcurrentHashMap<>();
//
//        public Dtls13Terminator(D13Props p) { this.p = p; }
//
//        @PostConstruct
//        public void start() throws Exception {
//            if (!p.isEnabled()) {
//                log.info("DTLS 1.3 terminator disabled.");
//                return;
//            }
//
//            // (1) DTLSv1.3 지원 JSSE Provider 확보(자동 탐색 + 후보군 시도)
//            Provider prov = ensureDtls13Provider(p.getProvider());
//            log.info("Using JSSE Provider: {} ({})", prov.getName(), prov.getInfo());
//
//            // (2) SSLContext(DTLSv1.3) 생성
//            this.sslCtx = SSLContext.getInstance(p.getProtocol(), prov);
//            this.sslCtx.init(buildKeyManagers(), buildTrustManagers(), new SecureRandom());
//
//            // (3) UDP 바인딩
//            this.selector = Selector.open();
//            this.ch = DatagramChannel.open(StandardProtocolFamily.INET);
//            this.ch.setOption(StandardSocketOptions.SO_RCVBUF, 1 << 20);
//            this.ch.setOption(StandardSocketOptions.SO_SNDBUF, 1 << 20);
//            this.ch.configureBlocking(false);
//            this.ch.bind(new InetSocketAddress(p.getListenPort()));
//            this.ch.register(selector, SelectionKey.OP_READ);
//            log.info("DTLS 1.3 terminator listening on udp/{}", p.getListenPort());
//
//            // (4) IO 루프
//            this.ioPool = Executors.newSingleThreadExecutor(r -> {
//                Thread t = new Thread(r, "dtls13-terminator");
//                t.setDaemon(true);
//                return t;
//            });
//            running = true;
//            ioPool.submit(this::eventLoop);
//        }
//
//        @PreDestroy
//        public void stop() {
//            running = false;
//            try { if (selector != null) selector.wakeup(); } catch (Exception ignore) {}
//            try { if (ioPool != null) ioPool.shutdownNow(); } catch (Exception ignore) {}
//            try { if (ch != null) ch.close(); } catch (Exception ignore) {}
//            sessions.values().forEach(Session::close);
//            sessions.clear();
//            log.info("DTLS 1.3 terminator stopped.");
//        }
//
//        /* ---------- Provider / 키/트러스트 빌더 ---------- */
//
//        private Provider ensureDtls13Provider(String desiredProviderName) {
//            // 이미 등록된 Provider 중 선호 이름이 있으면 그대로 사용
//            if (desiredProviderName != null) {
//                Provider pv = Security.getProvider(desiredProviderName);
//                if (pv != null) {
//                    try {
//                        SSLContext.getInstance("DTLSv1.3", pv);
//                        return pv;
//                    } catch (Exception ignore) { /* 실제 DTLSv1.3 미지원이면 계속 진행 */ }
//                }
//            }
//
//            // 현재 JVM에 로드된 Provider에서 DTLSv1.3 지원 탐색
//            for (Provider pv : Security.getProviders()) {
//                try {
//                    SSLContext.getInstance("DTLSv1.3", pv);
//                    return pv;
//                } catch (Exception ignore) {}
//            }
//
//            // wolfSSL 배포본별 후보 FQCN 시도
//            String[] candidates = new String[] {
//                    "com.wolfssl.provider.jsse.WolfSSLProvider",
//                    "com.wolfssl.provider.WolfSSLProvider",
//                    "com.wolfssl.jsse.WolfSSLProvider",
//                    "wolfssl.provider.jsse.WolfSSLProvider"
//            };
//            for (String fqcn : candidates) {
//                try {
//                    Class<?> c = Class.forName(fqcn);
//                    Provider pv = (Provider) c.getDeclaredConstructor().newInstance();
//                    Security.addProvider(pv);
//                    SSLContext.getInstance("DTLSv1.3", pv); // 실제 지원 확인
//                    return pv;
//                } catch (ClassNotFoundException e) {
//                    // 다음 후보
//                } catch (Throwable t) {
//                    // 찾았지만 초기화 실패/미지원 등 → 다음 후보
//                }
//            }
//
//            // 모두 실패 → 진단 메시지
//            StringBuilder diag = new StringBuilder();
//            diag.append("No JSSE Provider for DTLSv1.3 found.\n")
//                    .append("Loaded providers: ");
//            for (Provider pv : Security.getProviders()) diag.append(pv.getName()).append(" ");
//
//            diag.append("\nHint:\n")
//                    .append(" - wolfssl-jsse.jar, wolfssljni.jar 가 클래스패스(-Dloader.path=/opt/wolfssl)에 있어야 합니다.\n")
//                    .append(" - libwolfssl.so, libwolfssljni.so 가 LD_LIBRARY_PATH(/opt/wolfssl)에 있어야 합니다.\n")
//                    .append(" - 컨테이너/호스트 아키텍처와 .so 아키텍처(x86_64/arm64)가 일치해야 합니다.\n")
//                    .append(" - 사용 중인 wolfSSL 배포본의 Provider FQCN을 확인해 주세요.\n");
//
//            throw new IllegalStateException(diag.toString());
//        }
//
//        private KeyManager[] buildKeyManagers() throws KeyStoreException, CertificateException,
//                IOException, NoSuchAlgorithmException, UnrecoverableKeyException {
//            KeyStore ks = KeyStore.getInstance("PKCS12");
//            try (FileInputStream in = new FileInputStream(Objects.requireNonNull(p.getP12(), "dtls13.p12 not set"))) {
//                ks.load(in, p.getP12Pass() != null ? p.getP12Pass().toCharArray() : new char[0]);
//            }
//            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
//            kmf.init(ks, p.getP12Pass() != null ? p.getP12Pass().toCharArray() : new char[0]);
//            return kmf.getKeyManagers();
//        }
//
//        private TrustManager[] buildTrustManagers() throws KeyStoreException, CertificateException,
//                IOException, NoSuchAlgorithmException {
//            // 필요 시 별도 TrustStore 사용. 여기서는 p12 재사용(서버 체인 신뢰)
//            KeyStore ts = KeyStore.getInstance("PKCS12");
//            if (p.getP12() != null) {
//                try (FileInputStream in = new FileInputStream(p.getP12())) {
//                    ts.load(in, p.getP12Pass() != null ? p.getP12Pass().toCharArray() : new char[0]);
//                }
//            } else {
//                ts.load(null, null);
//            }
//            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
//            tmf.init(ts);
//            return tmf.getTrustManagers();
//        }
//
//        /* ---------- 이벤트 루프 / 세션 ---------- */
//
//        private void eventLoop() {
//            ByteBuffer netBuf = ByteBuffer.allocateDirect(p.getMtu()); // 수신 암호문 버퍼
//            while (running) {
//                try {
//                    int n = selector.select();
//                    if (!running) break;
//                    if (n == 0) continue;
//
//                    Iterator<SelectionKey> it = selector.selectedKeys().iterator();
//                    while (it.hasNext()) {
//                        SelectionKey key = it.next();
//                        it.remove();
//                        if (!key.isValid()) continue;
//
//                        if (key.isReadable()) {
//                            netBuf.clear();
//                            SocketAddress remote = ch.receive(netBuf);
//                            if (remote == null) continue;
//                            netBuf.flip();
//
//                            Session s = sessions.computeIfAbsent(remote, r -> new Session(r, sslCtx, p));
//                            s.onDatagramFromClient(netBuf);
//                        }
//                    }
//                } catch (Throwable t) {
//                    log.warn("eventLoop error: {}", t.toString());
//                }
//            }
//        }
//
//        /** 세션(클라이언트 주소 단위) */
//        private class Session {
//            final SocketAddress client;
//            final SSLEngine engine;
//            final ByteBuffer appIn = ByteBuffer.allocate(16 * 1024);      // 평문(CoAP) 입력
//            final ByteBuffer appOut = ByteBuffer.allocate(16 * 1024);     // 평문(CoAP) 출력
//            final ByteBuffer netIn = ByteBuffer.allocateDirect(p.getMtu());
//            final ByteBuffer netOut = ByteBuffer.allocateDirect(p.getMtu());
//
//            Session(SocketAddress client, SSLContext ctx, D13Props props) {
//                this.client = client;
//                this.engine = ctx.createSSLEngine();
//                SSLParameters sp = new SSLParameters();
//                sp.setCipherSuites(props.getCiphers().toArray(new String[0]));
//                sp.setNeedClientAuth(props.isNeedClientAuth());
//                engine.setUseClientMode(false);
//                engine.setSSLParameters(sp);
//
//                // 일부 배포는 DTLS MTU 설정 메서드 제공
//                try {
//                    engine.getClass().getMethod("setDTLSMtu", int.class).invoke(engine, props.getMtu());
//                } catch (Exception ignore) {}
//            }
//
//            void onDatagramFromClient(ByteBuffer dat) throws Exception {
//                netIn.clear();
//                netIn.put(dat);
//                netIn.flip();
//
//                while (netIn.hasRemaining()) {
//                    SSLEngineResult res = engine.unwrap(netIn, appIn);
//                    switch (res.getStatus()) {
//                        case OK -> {
//                            switch (res.getHandshakeStatus()) {
//                                case NEED_TASK -> runTasks();
//                                case NEED_WRAP -> flushToClient(); // 핸드셰이크 송신
//                                case FINISHED, NOT_HANDSHAKING -> {
//                                    if (appIn.position() > 0) {
//                                        appIn.flip();
//                                        byte[] req = new byte[appIn.remaining()];
//                                        appIn.get(req);
//                                        appIn.clear();
//                                        byte[] resp = queryUpstream(req); // 평문 CoAP로 전달
//                                        if (resp != null) sendAppToClient(ByteBuffer.wrap(resp));
//                                    }
//                                }
//                                case NEED_UNWRAP -> { /* 계속 수신 */ }
//                            }
//                        }
//                        case BUFFER_OVERFLOW -> growAppIn();
//                        case BUFFER_UNDERFLOW -> { return; } // 더 받기
//                        case CLOSED -> { close(); return; }
//                    }
//                }
//            }
//
//            private void sendAppToClient(ByteBuffer plain) throws Exception {
//                netOut.clear();
//                while (plain.hasRemaining()) {
//                    SSLEngineResult res = engine.wrap(plain, netOut);
//                    if (res.getStatus() == SSLEngineResult.Status.CLOSED) { close(); return; }
//                    if (res.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) { growNetOut(); continue; }
//                }
//                netOut.flip();
//                ch.send(netOut, client);
//            }
//
//            private void flushToClient() throws Exception {
//                netOut.clear();
//                SSLEngineResult res = engine.wrap(ByteBuffer.allocate(0), netOut);
//                if (res.getStatus() == SSLEngineResult.Status.OK && netOut.position() > 0) {
//                    netOut.flip();
//                    ch.send(netOut, client);
//                }
//                if (res.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK) runTasks();
//            }
//
//            private void runTasks() {
//                Runnable r;
//                while ((r = engine.getDelegatedTask()) != null) r.run();
//            }
//
//            private void growAppIn() { /* 필요 시 버퍼 확장 구현 */ }
//            private void growNetOut() { /* 필요 시 버퍼 확장 구현 */ }
//
//            private byte[] queryUpstream(byte[] req) {
//                try (DatagramSocket s = new DatagramSocket()) {
//                    s.setSoTimeout(p.getUpstreamTimeoutMs());
//                    DatagramPacket out = new DatagramPacket(req, req.length,
//                            InetAddress.getByName(p.getUpstreamHost()), p.getUpstreamPort());
//                    s.send(out);
//
//                    byte[] buf = new byte[1500];
//                    DatagramPacket in = new DatagramPacket(buf, buf.length);
//                    s.receive(in);
//                    return Arrays.copyOf(in.getData(), in.getLength());
//                } catch (Exception e) {
//                    log.debug("upstream timeout or error: {}", e.toString());
//                    return null;
//                }
//            }
//
//            void close() {
//                try { engine.closeOutbound(); } catch (Exception ignore) {}
//            }
//        }
//    }
//}
