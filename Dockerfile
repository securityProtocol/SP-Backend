# ===== 1) 앱 빌드 (Gradle) =====
FROM gradle:8.9-jdk21 AS buildapp
WORKDIR /src
COPY gradlew ./
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN ./gradlew --version
RUN ./gradlew -q dependencies || true
COPY . .
RUN ./gradlew --no-daemon clean bootJar -x test

# ===== 2) wolfSSL + wolfSSL JNI/JSSE 빌드 =====
FROM ubuntu:24.04 AS wolfbuild
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential ca-certificates pkg-config \
    openjdk-21-jdk ant unzip curl git \
    autoconf automake libtool cmake \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /deps
# 로컬에 미리 받아둔 ZIP 파일 이름과 일치해야 합니다.
COPY vendor/wolfssl-5.8.2.zip .
COPY vendor/wolfssl-jni-jsse-1.15.0.zip .

# 2-1) wolfSSL (네이티브)
RUN unzip -q wolfssl-5.8.2.zip && \
    cd wolfssl-5.8.2 && \
    ./configure \
      --enable-dtls --enable-dtls13 --enable-tls13 \
      --enable-aesgcm --enable-chacha \
      --enable-opensslextra --enable-jni \
    && make -j"$(nproc)" && make install && ldconfig

# 2-2) wolfSSL JNI/JSSE (Ant 기반)
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ENV WOLFSSL_INSTALL_DIR=/usr/local
RUN unzip -q wolfssl-jni-jsse-1.15.0.zip && \
    cd wolfssl-jni-jsse-1.15.0 && \
    ./java.sh && \
    ( [ -f java/build.xml ] && ant -noinput -f java/build.xml || \
      [ -f build.xml ] && ant -noinput -f build.xml || true ) && \
    mkdir -p /opt/wolfssl && \
    cp -av lib/libwolfssljni.so* /opt/wolfssl/ && \
    find . -type f \( -name 'wolfssl-jsse*.jar' -o -name 'wolfssljni*.jar' \) -exec cp -av {} /opt/wolfssl/ \; || true && \
    cp -av /usr/local/lib/libwolfssl.so* /opt/wolfssl/

# ===== 3) 런타임 =====
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=buildapp /src/build/libs/*.jar /app/app.jar
COPY --from=wolfbuild /opt/wolfssl /opt/wolfssl
ENV LD_LIBRARY_PATH="/opt/wolfssl:${LD_LIBRARY_PATH}"
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Dloader.path=/opt/wolfssl"
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar","--server.port=8080","--server.forward-headers-strategy=framework"]
