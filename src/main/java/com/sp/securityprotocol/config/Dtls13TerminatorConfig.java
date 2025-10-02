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
///**
// * 외부 DTLS 1.3(UDP 9001 등) ⇄ 내부 평문 CoAP(udp/5683) 프록시용 Terminator.
// * - DTLS 1.3은 JSSE Provider(wolfSSL 등)가 필요. (JAR/so 배치 필수)
// * - 앱 실행 전, 컨테이너/호스트에 아래가 있어야 함:
// *   * 클래스패스: wolfssl-jsse*.jar, wolfssljni*.jar  (예: -Dloader.path=/opt/wolfssl)
// *   * 네이티브:   libwolfssl.so, libwolfssljni.so   (예: LD_LIBRARY_PATH=/opt/wolfssl)
// */
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
//            log.info("Starting DTLS 1.3 terminator...");
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
//            // 0) 먼저 현재 등록된 Provider 중에서 DTLSv1.3 가능한지 탐색
//            if (desiredProviderName != null) {
//                Provider got = Security.getProvider(desiredProviderName);
//                log.info("Desired JSSE provider '{}' registered: {}", desiredProviderName, got != null ? got.getName() : "not found");
//                if (got != null) {
//                    try {
//                        SSLContext.getInstance("DTLSv1.3", got);
//                        return got;
//                    } catch (Exception ignore) {
//                        // 이름은 맞는데 DTLSv1.3 미지원 → 계속 진행
//                    }
//                } else {
//                    // 이름으로 못 찾은 경우에도 아래에서 동적 로드 시도
//                    log.info("Desired JSSE provider '{}' not registered yet, trying to load dynamically.", desiredProviderName);
//                }
//            }
//            for (Provider p0 : Security.getProviders()) {
//                try {
//                    log.info("Checking provider: {}", p0.getName());
//                    SSLContext.getInstance("DTLSv1.3", p0);
//                    log.info("Found DTLSv1.3 support from already-registered provider: {}", p0.getName());
//                    return p0;
//                } catch (Exception ignore) {}
//            }
//
//            // 1) wolfSSL 후보 FQCN들
//            final String[] candidates = new String[] {
//                    "com.wolfssl.provider.jsse.WolfSSLProvider",  // 최신 JNI/JSSE 배포에서 주로 이 형태
//                    "com.wolfssl.provider.WolfSSLProvider",
//                    "com.wolfssl.jsse.WolfSSLProvider",
//                    "wolfssl.provider.jsse.WolfSSLProvider"
//            };
//
//            // 2) 우선 현재 classloader로 로드 시도
//            for (String fqcn : candidates) {
//                try {
//                    Class<?> c = Class.forName(fqcn);
//                    Provider pv = (Provider) c.getDeclaredConstructor().newInstance();
//                    Security.addProvider(pv);
//                    SSLContext.getInstance("DTLSv1.3", pv); // 실제 지원 확인
//                    log.info("Loaded JSSE provider via current classloader: {}", fqcn);
//                    return pv;
//                } catch (ClassNotFoundException e) {
//                    // 다음 시도
//                } catch (Throwable t) {
//                    log.debug("Provider init failed for {}: {}", fqcn, t.toString());
//                }
//            }
//
//            // 3) /opt/wolfssl 아래 JAR을 직접 로더로 붙여서 다시 시도
//            java.nio.file.Path libDir = java.nio.file.Paths.get(
//                    System.getProperty("wolfssl.dir", "/opt/wolfssl")
//            );
//            java.util.List<java.net.URL> jarUrls = new java.util.ArrayList<>();
//            try (java.util.stream.Stream<java.nio.file.Path> s =
//                         java.nio.file.Files.exists(libDir)
//                                 ? java.nio.file.Files.list(libDir)
//                                 : java.util.stream.Stream.empty()) {
//                s.filter(p -> p.toString().endsWith(".jar")).forEach(p -> {
//                    try { jarUrls.add(p.toUri().toURL()); } catch (Exception ignore) {}
//                });
//            } catch (Exception ignore) {}
//
//            if (!jarUrls.isEmpty()) {
//                try (java.net.URLClassLoader ucl =
//                             new java.net.URLClassLoader(jarUrls.toArray(new java.net.URL[0]),
//                                     Thread.currentThread().getContextClassLoader())) {
//                    for (String fqcn : candidates) {
//                        try {
//                            Class<?> c = java.lang.Class.forName(fqcn, true, ucl);
//                            Provider pv = (Provider) c.getDeclaredConstructor().newInstance();
//                            Security.addProvider(pv);
//                            SSLContext.getInstance("DTLSv1.3", pv); // 지원 검증
//                            log.info("Loaded JSSE provider via /opt/wolfssl jars: {}", fqcn);
//                            return pv;
//                        } catch (ClassNotFoundException e) {
//                            // 다음 후보
//                        } catch (Throwable t) {
//                            log.debug("URLClassLoader init failed for {}: {}", fqcn, t.toString());
//                        }
//                    }
//                } catch (Exception e) {
//                    log.debug("Failed to create URLClassLoader for /opt/wolfssl: {}", e.toString());
//                }
//            } else {
//                log.warn("No JARs found under {}", libDir);
//            }
//
//            // 4) 모두 실패 → 진단 메시지
//            StringBuilder diag = new StringBuilder();
//            diag.append("No JSSE Provider for DTLSv1.3 found.\nLoaded providers: ");
//            for (Provider pv : Security.getProviders()) diag.append(pv.getName()).append(" ");
//            diag.append("\nHint:\n")
//                    .append(" - /opt/wolfssl 에 wolfssl-jsse*.jar, wolfssljni*.jar 이 있어야 합니다.\n")
//                    .append(" - /opt/wolfssl 에 libwolfssl.so, libwolfssljni.so 가 있어야 합니다.\n")
//                    .append(" - LD_LIBRARY_PATH=/opt/wolfssl, -Dloader.path=/opt/wolfssl 가 설정돼야 합니다.\n")
//                    .append(" - 아키텍처(x86_64/arm64)가 컨테이너와 일치해야 합니다.\n")
//                    .append(" - provider 이름을 꼭 지정할 필요는 없습니다. (설정에서 coap.dtls13.provider를 비우면 자동탐색)\n");
//            throw new IllegalStateException(diag.toString());
//        }
//
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
//            final ByteBuffer scratch = ByteBuffer.allocateDirect(p.getMtu()); // 수신 암호문 버퍼
//            while (running) {
//                try {
//                    int n = selector.select(250); // 250ms 타임아웃으로 깨어나기
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
//                            scratch.clear();
//                            SocketAddress remote = ch.receive(scratch);
//                            if (remote == null) continue;
//                            scratch.flip();
//
//                            Session s = sessions.computeIfAbsent(remote, r -> new Session(r, sslCtx, p));
//                            s.onDatagramFromClient(scratch);
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
//            ByteBuffer appIn = ByteBuffer.allocate(16 * 1024);      // 평문(CoAP) 입력
//            ByteBuffer appOut = ByteBuffer.allocate(16 * 1024);     // 평문(CoAP) 출력
//            ByteBuffer netIn = ByteBuffer.allocateDirect(p.getMtu());
//            ByteBuffer netOut = ByteBuffer.allocateDirect(p.getMtu());
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
//
//                log.debug("[{}] session created", client);
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
//
//                                        byte[] resp = queryUpstream(req); // 평문 CoAP로 전달
//                                        if (resp != null) sendAppToClient(ByteBuffer.wrap(resp));
//                                    }
//                                }
//                                case NEED_UNWRAP -> { /* 계속 수신 */ }
//                            }
//                        }
//                        case BUFFER_OVERFLOW -> { growAppIn(); }
//                        case BUFFER_UNDERFLOW -> { return; } // 더 받기
//                        case CLOSED -> { close(); return; }
//                    }
//                }
//            }
//
//            private void sendAppToClient(ByteBuffer plain) throws Exception {
//                while (plain.hasRemaining()) {
//                    netOut.clear();
//                    SSLEngineResult res = engine.wrap(plain, netOut);
//                    switch (res.getStatus()) {
//                        case OK -> {
//                            netOut.flip();
//                            if (netOut.hasRemaining()) ch.send(netOut, client);
//                            if (res.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK) runTasks();
//                        }
//                        case BUFFER_OVERFLOW -> { growNetOut(); }
//                        case CLOSED -> { close(); return; }
//                        case BUFFER_UNDERFLOW -> { /* wrap에서는 거의 없음 */ }
//                    }
//                }
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
//                while ((r = engine.getDelegatedTask()) != null) {
//                    try { r.run(); } catch (Throwable t) { log.debug("delegated task error: {}", t.toString()); }
//                }
//            }
//
//            private void growAppIn() {
//                int old = appIn.capacity();
//                int neu = Math.min(old * 2, 1 << 20); // 최대 1MB
//                if (neu == old) return;
//                ByteBuffer bigger = ByteBuffer.allocate(neu);
//                appIn.flip();
//                bigger.put(appIn);
//                appIn = bigger;
//                log.debug("appIn buffer grown: {} -> {}", old, neu);
//            }
//
//            private void growNetOut() {
//                int old = netOut.capacity();
//                int neu = Math.min(old * 2, 64 * 1024); // DTLS 레코드 크기 고려, 64KB 제한
//                if (neu == old) return;
//                ByteBuffer bigger = ByteBuffer.allocateDirect(neu);
//                netOut.flip();
//                bigger.put(netOut);
//                netOut = bigger;
//                log.debug("netOut buffer grown: {} -> {}", old, neu);
//            }
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
//                log.debug("[{}] session closed", client);
//            }
//        }
//    }
//}
