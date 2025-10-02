# --- wolfSSL + wolfSSL JNI/JSSE 빌드 스테이지 ---
FROM ubuntu:24.04 AS wolfbuild

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential ca-certificates pkg-config \
    openjdk-21-jdk ant unzip curl git \
    autoconf automake libtool cmake \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /deps
# 여기 두 zip은 이미 vendor/ 밑에 있습니다(이름 정확히 일치해야 함)
COPY vendor/wolfssl-5.8.2.zip .
COPY vendor/wolfssl-jni-jsse-1.15.0.zip .

# 1) wolfSSL(네이티브) 빌드/설치  — DTLS 1.3에 필요한 옵션 활성화
RUN unzip -q wolfssl-5.8.2.zip && \
    cd wolfssl-5.8.2 && \
    ./configure \
      --enable-dtls --enable-dtls13 --enable-tls13 \
      --enable-aesgcm --enable-chacha \
      --enable-opensslextra --enable-jni \
    && make -j"$(nproc)" && make install && ldconfig

# 2) wolfSSL JNI/JSSE 빌드  — 이 패키지는 ant/Makefile로 JAR을 만듭니다
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ENV WOLFSSL_INSTALL_DIR=/usr/local
RUN unzip -q wolfssl-jni-jsse-1.15.0.zip && \
    cd wolfssl-jni-jsse-1.15.0 && \
    # 네이티브 JNI so 빌드 (java.sh가 내부에서 컴파일 진행)
    ./java.sh && \
    # JAR 빌드(ant 필요). 패키지에 따라 build.xml 위치가 다를 수 있어 두 가지 경로 시도.
    ( [ -f java/build.xml ] && ant -noinput -f java/build.xml ) || \
    ( [ -f build.xml ] && ant -noinput -f build.xml ) || true && \
    # 아티팩트 수집
    mkdir -p /opt/wolfssl && \
    cp -av lib/libwolfssljni.so* /opt/wolfssl/ && \
    find . -type f \( -name 'wolfssl-jsse*.jar' -o -name 'wolfssljni*.jar' \) -exec cp -av {} /opt/wolfssl/ \; || true && \
    # wolfSSL의 공유 라이브러리도 함께 보존
    cp -av /usr/local/lib/libwolfssl.so* /opt/wolfssl/

# 런타임으로 넘길 디렉터리: /opt/wolfssl


# --- 기존 런타임 스테이지 (예: eclipse-temurin:21-jre-jammy) ---
FROM eclipse-temurin:21-jre-jammy AS stage-2
WORKDIR /app

# app.jar 복사 (기존 그대로)
COPY --from=build /src/build/libs/*.jar /app/app.jar

# wolfSSL 네이티브/JAR 복사
COPY --from=wolfbuild /opt/wolfssl /opt/wolfssl

# 네이티브/클래스패스 경로 설정 (Spring Boot Loader로 외부 JAR 로딩)
ENV LD_LIBRARY_PATH="/opt/wolfssl:${LD_LIBRARY_PATH}"
ENV CLASSPATH="/opt/wolfssl/*:${CLASSPATH}"

# 또는 Spring Boot 실행 시
# -Dloader.path=/opt/wolfssl 로 외부 JAR 추가
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Dloader.path=/opt/wolfssl"

EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar","--server.port=8080","--server.forward-headers-strategy=framework"]

