package com.sp.securityprotocol.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.*;
import java.io.FileInputStream;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.security.KeyStore;
import java.security.Security;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Configuration
public class Dtls13TerminatorConfig {
    @Bean
    @ConfigurationProperties(prefix = "coap.dtls13")
    public Dtls13Props dtls13Props() { return new Dtls13Props(); }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public Dtls13Terminator dtls13Terminator(Dtls13Props p) { return new Dtls13Terminator(p); }

    public static class Dtls13Props {
        /** 외부 DTLS1.3 수신 포트 (nginx upstream 대상) */
        private int listenPort = 9001;
        /** 내부 평문 CoAP 목적지 */
        private String upstreamHost = "127.0.0.1";
        private int upstreamPort = 5683;

        /** 키스토어(JKS/PKCS12) 경로 & 패스워드 (서버 인증서=fullchain 포함, 개인키) */
        private String keystorePath;  // 예: /certs/coaps.p12
        private String keystoreType = "PKCS12";
        private String keystorePass;
        private String keyPass;

        /** TLS1.3 전용 사이퍼만 허용(기본) */
        private List<String> enabledCiphers = Arrays.asList(
                "TLS_AES_128_GCM_SHA256",
                "TLS_AES_256_GCM_SHA384",
                "TLS_CHACHA20_POLY1305_SHA256"
        );

        /** MTU 및 버퍼 */
        private int mtu = 1200;
        private int maxSessions = 10000;
        private long sessionIdleMillis = 60_000;

        /** wolfJSSE 프로바이더 명 (환경에 맞게) */
        private String provider = "wolfJSSE";

        // getters/setters
        public int getListenPort() { return listenPort; }
        public void setListenPort(int listenPort) { this.listenPort = listenPort; }
        public String getUpstreamHost() { return upstreamHost; }
        public void setUpstreamHost(String upstreamHost) { this.upstreamHost = upstreamHost; }
        public int getUpstreamPort() { return upstreamPort; }
        public void setUpstreamPort(int upstreamPort) { this.upstreamPort = upstreamPort; }
        public String getKeystorePath() { return keystorePath; }
        public void setKeystorePath(String keystorePath) { this.keystorePath = keystorePath; }
        public String getKeystoreType() { return keystoreType; }
        public void setKeystoreType(String keystoreType) { this.keystoreType = keystoreType; }
        public String getKeystorePass() { return keystorePass; }
        public void setKeystorePass(String keystorePass) { this.keystorePass = keystorePass; }
        public String getKeyPass() { return keyPass; }
        public void setKeyPass(String keyPass) { this.keyPass = keyPass; }
        public List<String> getEnabledCiphers() { return enabledCiphers; }
        public void setEnabledCiphers(List<String> enabledCiphers) { this.enabledCiphers = enabledCiphers; }
        public int getMtu() { return mtu; }
        public void setMtu(int mtu) { this.mtu = mtu; }
        public int getMaxSessions() { return maxSessions; }
        public void setMaxSessions(int maxSessions) { this.maxSessions = maxSessions; }
        public long getSessionIdleMillis() { return sessionIdleMillis; }
        public void setSessionIdleMillis(long sessionIdleMillis) { this.sessionIdleMillis = sessionIdleMillis; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
    }

    /** DTLS1.3 over UDP → 평문 CoAP(UDP) 프록시 */
    public static class Dtls13Terminator {
        private static final Logger log = LoggerFactory.getLogger(Dtls13Terminator.class);

        private final Dtls13Props p;

        private DatagramChannel listenCh;
        private final Map<SocketAddress, Session> sessions = new ConcurrentHashMap<>();
        private volatile boolean running;

        private ExecutorService ioPool;
        private ScheduledExecutorService housekeeper;

        public Dtls13Terminator(Dtls13Props p) { this.p = p; }

        @PostConstruct
        public void start() throws Exception {
            // 1) wolfJSSE 프로바이더 등록 (이미 등록되어 있으면 skip)
            try {
                if (Security.getProvider(p.getProvider()) == null) {
                    // 예: Security.addProvider(new com.wolfssl.provider.jsse.WolfSSLProvider());
                    // 실제 클래스는 wolfJSSE 배포물에 맞게 추가하세요.
                    Class<?> provClazz = Class.forName("com.wolfssl.provider.jsse.WolfSSLProvider");
                    Security.addProvider((java.security.Provider)provClazz.getDeclaredConstructor().newInstance());
                }
            } catch (Throwable t) {
                throw new IllegalStateException("wolfJSSE provider load failed. Check classpath & native libs.", t);
            }

            // 2) SSLContext(DTLSv1.3) 생성
            SSLContext sslCtx = SSLContext.getInstance("DTLSv1.3", p.getProvider());
            KeyStore ks = KeyStore.getInstance(p.getKeystoreType());
            try (FileInputStream fis = new FileInputStream(p.getKeystorePath())) {
                ks.load(fis, p.getKeystorePass().toCharArray());
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, p.getKeyPass().toCharArray());
            // 클라이언트 인증이 필요하면 TMF 설정
            sslCtx.init(kmf.getKeyManagers(), null, null);

            // 3) 리슨 채널
            listenCh = DatagramChannel.open(StandardProtocolFamily.INET);
            listenCh.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            listenCh.configureBlocking(false);
            listenCh.bind(new InetSocketAddress(p.getListenPort()));
            log.info("DTLS1.3 terminator listening on udp/{}", p.getListenPort());

            ioPool = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
            housekeeper = Executors.newSingleThreadScheduledExecutor();

            running = true;
            ioPool.submit(() -> recvLoop(sslCtx));
            housekeeper.scheduleAtFixedRate(this::sweepIdle, 10, 10, TimeUnit.SECONDS);
        }

        @PreDestroy
        public void stop() {
            running = false;
            try { if (listenCh != null) listenCh.close(); } catch (Exception ignore) {}
            sessions.values().forEach(Session::close);
            sessions.clear();
            if (ioPool != null) ioPool.shutdownNow();
            if (housekeeper != null) housekeeper.shutdownNow();
            log.info("DTLS1.3 terminator stopped.");
        }

        private void recvLoop(SSLContext sslCtx) {
            ByteBuffer netBuf = ByteBuffer.allocateDirect(Math.max(2048, p.getMtu()*2));
            while (running) {
                try {
                    netBuf.clear();
                    SocketAddress remote = listenCh.receive(netBuf);
                    if (remote == null) {
                        Thread.onSpinWait();
                        continue;
                    }
                    netBuf.flip();

                    Session s = sessions.computeIfAbsent(remote, r -> {
                        if (sessions.size() >= p.getMaxSessions()) {
                            log.warn("Too many DTLS sessions, dropping {}", r);
                            return null;
                        }
                        try { return new Session(sslCtx, r, p); }
                        catch (Exception e) {
                            log.error("Session init failed for {}: {}", r, e.toString());
                            return null;
                        }
                    });
                    if (s == null) continue;

                    s.onDatagramFromClient(netBuf);
                } catch (Exception e) {
                    if (running) log.warn("recvLoop error: {}", e.toString());
                }
            }
        }

        private void sweepIdle() {
            long now = System.currentTimeMillis();
            for (Map.Entry<SocketAddress, Session> e : sessions.entrySet()) {
                if (now - e.getValue().lastActive > p.getSessionIdleMillis()) {
                    Session s = sessions.remove(e.getKey());
                    if (s != null) {
                        log.debug("Session idle timeout: {}", e.getKey());
                        s.close();
                    }
                }
            }
        }

        /** 1개 클라이언트와의 DTLS 1.3 세션 상태 */
        private final class Session {
            final SocketAddress client;
            final SSLEngine engine;
            final DatagramSocket upstream; // 평문 CoAP(UDP)로 handoff
            final ByteBuffer netIn;     // DTLS record in
            final ByteBuffer netOut;    // DTLS record out
            ByteBuffer appIn;           // decrypted (CoAP)
            ByteBuffer appOut;          // encrypted (wrap input)
            volatile long lastActive = System.currentTimeMillis();

            Session(SSLContext sslCtx, SocketAddress client, Dtls13Props p) throws Exception {
                this.client = client;

                SSLParameters params = new SSLParameters();
                // DTLS라서 setProtocols가 아닌 엔진에서 enable (아래)
                params.setUseCipherSuitesOrder(true);

                engine = sslCtx.createSSLEngine();
                engine.setUseClientMode(false);
                engine.setEnabledProtocols(new String[]{"DTLSv1.3"});
                engine.setEnabledCipherSuites(p.getEnabledCiphers().toArray(new String[0]));
                engine.setSSLParameters(params);

                // 버퍼 사이즈
                SSLSession sess = engine.getSession();
                int packet = Math.max(p.getMtu(), sess.getPacketBufferSize());
                int app    = Math.max(2048, sess.getApplicationBufferSize());

                netIn  = ByteBuffer.allocateDirect(packet);
                netOut = ByteBuffer.allocateDirect(packet);
                appIn  = ByteBuffer.allocate(app);
                appOut = ByteBuffer.allocate(app);

                // 업스트림(평문 CoAP)
                upstream = new DatagramSocket();
                upstream.setSoTimeout(3000);

                engine.beginHandshake();
                handshake();
            }

            void onDatagramFromClient(ByteBuffer buf) throws Exception {
                lastActive = System.currentTimeMillis();
                // 클라에서 온 DTLS 레코드를 언랩
                unwrapLoop(buf);

                // appIn에 평문 CoAP 메시지가 쌓였으면 → upstream으로 포워딩
                appIn.flip();
                if (appIn.hasRemaining()) {
                    // 보낸 다음 응답 수신(단순 동기 라운드트립; 필요시 비동기화)
                    byte[] req = new byte[appIn.remaining()];
                    appIn.get(req);
                    appIn.clear();

                    DatagramPacket out = new DatagramPacket(req, req.length,
                            InetAddress.getByName(p.getUpstreamHost()), p.getUpstreamPort());
                    upstream.send(out);

                    byte[] resp = new byte[p.getMtu()];
                    DatagramPacket in = new DatagramPacket(resp, resp.length);
                    upstream.receive(in);

                    // 응답을 DTLS로 감싸서 클라이언트로 송신
                    wrapAndSend(ByteBuffer.wrap(resp, 0, in.getLength()));
                }
            }

            private void handshake() throws Exception {
                while (true) {
                    SSLEngineResult.HandshakeStatus hs = engine.getHandshakeStatus();
                    switch (hs) {
                        case NEED_WRAP -> {
                            netOut.clear();
                            SSLEngineResult r = engine.wrap(ByteBuffer.allocate(0), netOut);
                            checkResult(r);
                            netOut.flip();
                            if (netOut.hasRemaining()) {
                                listenCh.send(netOut, client);
                            }
                        }
                        case NEED_UNWRAP, NEED_UNWRAP_AGAIN -> {
                            // 핸드셰이크 초기에 클라 입력이 더 필요. 상위 onDatagramFromClient()에서 계속 들어옴.
                            return;
                        }
                        case NEED_TASK -> runTasks();
                        case FINISHED, NOT_HANDSHAKING -> {
                            log.debug("DTLS handshake complete: {}", client);
                            return;
                        }
                    }
                }
            }

            private void unwrapLoop(ByteBuffer incoming) throws Exception {
                while (incoming.hasRemaining()) {
                    SSLEngineResult r = engine.unwrap(incoming, appIn);
                    switch (r.getStatus()) {
                        case BUFFER_OVERFLOW -> {
                            // appIn 확장
                            ByteBuffer bigger = ByteBuffer.allocate(appIn.capacity() * 2);
                            appIn.flip(); bigger.put(appIn); appIn = bigger;
                        }
                        case BUFFER_UNDERFLOW -> {
                            // DTLS 패킷이 더 필요(조각) → 다음 datagram 때까지 대기
                            return;
                        }
                        case OK -> {
                            // 핸드셰이크 단계면 wrap 필요할 수 있음
                            if (r.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                                netOut.clear();
                                SSLEngineResult wr = engine.wrap(ByteBuffer.allocate(0), netOut);
                                checkResult(wr);
                                netOut.flip();
                                if (netOut.hasRemaining()) {
                                    listenCh.send(netOut, client);
                                }
                            } else if (r.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                                runTasks();
                            }
                            if (r.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED ||
                                    r.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                                // 정상 데이터 수신 가능
                            }
                        }
                        case CLOSED -> { close(); return; }
                    }
                }
            }

            private void wrapAndSend(ByteBuffer plaintext) throws Exception {
                while (plaintext.hasRemaining()) {
                    netOut.clear();
                    SSLEngineResult r = engine.wrap(plaintext, netOut);
                    checkResult(r);
                    netOut.flip();
                    if (netOut.hasRemaining()) {
                        listenCh.send(netOut, client);
                    }
                    if (r.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK) runTasks();
                }
            }

            private void runTasks() {
                Runnable task;
                while ((task = engine.getDelegatedTask()) != null) {
                    task.run();
                }
            }

            private void checkResult(SSLEngineResult r) {
                if (r.getStatus() == SSLEngineResult.Status.CLOSED) {
                    throw new IllegalStateException("DTLS engine closed");
                }
            }

            void close() {
                try { engine.closeOutbound(); } catch (Exception ignore) {}
                try { upstream.close(); } catch (Exception ignore) {}
            }
        }
    }
}
