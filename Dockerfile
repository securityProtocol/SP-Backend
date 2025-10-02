# ===== 1) Build wolfSSL + wolfSSL JNI/JSSE =====
FROM ubuntu:24.04 AS wolfbuild
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential cmake git pkg-config unzip ca-certificates \
    openjdk-21-jdk-headless \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /deps
# 로컬에 둔 벤더 zip 복사
COPY vendor/wolfssl-5.8.2.zip .
COPY vendor/wolfssl-jni-jsse-1.15.0.zip .

# 1-1) wolfSSL 네이티브 빌드 (DTLS+TLS1.3+공유라이브러리)
RUN unzip -q wolfssl-5.8.2.zip && \
    cd wolfssl-* && \
    cmake -S . -B build \
      -DBUILD_SHARED_LIBS=ON \
      -DWOLFSSL_DTLS=ON \
      -DWOLFSSL_TLS13=ON \
      -DWOLFSSL_OPENSSLEXTRA=ON \
      -DWOLFSSL_ERROR_QUEUE=ON && \
    cmake --build build -j"$(nproc)" && \
    cmake --install build

# 1-2) wolfSSL JNI/JSSE 빌드 (자바 바인딩 + Provider JAR)
# 패키지 내부 빌드 시스템 차이를 감안해 CMake 우선, 안되면 autoconf 폴백
RUN unzip -q wolfssl-jni-jsse-1.15.0.zip && \
    cd wolfssl-jni-jsse-* && \
    ( cmake -S . -B build \
        -DWITH_WOLFSSL=/usr/local \
        -DBUILD_JSSE=ON \
        -DBUILD_EXAMPLES=OFF \
      && cmake --build build -j"$(nproc)" \
      || ( [ -f ./autogen.sh ] && ./autogen.sh || true; \
           ./configure --with-wolfssl=/usr/local; \
           make -j"$(nproc)" ) )

# 산출물 한 곳으로 모으기
RUN mkdir -p /opt/wolfssl && \
    cp -av /usr/local/lib/libwolfssl.so* /opt/wolfssl/ && \
    find /deps -maxdepth 3 -type f -name "libwolfssljni.so*" -exec cp -av {} /opt/wolfssl/ \; && \
    # JAR (wolfssl-jsse.jar, wolfssljni.jar)
    find /deps -maxdepth 4 -type f -name "wolfssl-jsse*.jar" -exec cp -av {} /opt/wolfssl/ \; && \
    find /deps -maxdepth 4 -type f -name "wolfssljni*.jar" -exec cp -av {} /opt/wolfssl/ \;

# ===== 2) Build app (Gradle) =====
FROM gradle:8.9-jdk21 AS buildapp
WORKDIR /src
COPY gradlew ./
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN ./gradlew --version
RUN ./gradlew -q dependencies || true
COPY . .
RUN ./gradlew --no-daemon clean bootJar -x test

# ===== 3) Runtime =====
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
# wolfSSL 산출물 배치
COPY --from=wolfbuild /opt/wolfssl /opt/wolfssl
# 앱 JAR 배치
COPY --from=buildapp /src/build/libs/*.jar /app/app.jar

# 런타임 환경
ENV LD_LIBRARY_PATH="/opt/wolfssl"
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

EXPOSE 8080 9001/udp
# loader.path로 외부 JAR(Provider) 로드, JSSE 기본 프로토콜을 dtls로 강제
ENTRYPOINT ["java","-Dloader.path=/opt/wolfssl","-Djava.library.path=/opt/wolfssl","-Dssl.ServerSocketFactory.provider=com.wolfssl.provider.jsse.WolfSSLProvider","-Dssl.SocketFactory.provider=com.wolfssl.provider.jsse.WolfSSLProvider","-jar","/app/app.jar","--server.port=8080","--server.forward-headers-strategy=framework"]
