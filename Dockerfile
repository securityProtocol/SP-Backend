# ---- 1) Build stage: wolfSSL + wolfssljni(JSSE) 빌드 ----
FROM ubuntu:24.04 AS wolfbuild
ARG DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential autoconf automake libtool pkg-config git ca-certificates \
    openjdk-21-jdk maven wget unzip \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
# (arm64일 때 자동 경로가 다를 수 있으니, 필요시 docker build ARG/TARGETARCH로 분기해도 됨)

WORKDIR /build

# 1-1) wolfSSL 소스 빌드 (DTLS 1.3 활성)
RUN git clone --depth=1 https://github.com/wolfSSL/wolfssl.git
WORKDIR /build/wolfssl
RUN ./autogen.sh && \
    ./configure \
      --enable-dtls --enable-dtls13 \
      --enable-tls13 \
      --enable-opensslextra \
      --enable-shared && \
    make -j$(nproc) && make install
# 설치 경로 기본 /usr/local, so: /usr/local/lib/libwolfssl.so

# 1-2) wolfSSL JNI/JSSE 빌드 (wolfJSSE)
WORKDIR /build
RUN git clone --depth=1 https://github.com/wolfSSL/wolfssljni.git
WORKDIR /build/wolfssljni
# 환경에 따라 autogen.sh가 없는 버전도 있어 ./autogen.sh 있으면 실행
RUN [ -f ./autogen.sh ] && ./autogen.sh || true
RUN ./configure --with-wolfssl=/usr/local \
    && make -j$(nproc)

# 산출물 위치 정리
# (프로젝트 버전에 따라 출력 경로가 조금 다를 수 있음: 아래 find로 수집)
RUN mkdir -p /out/wolfssl && \
    cp -av /usr/local/lib/libwolfssl.so* /out/wolfssl/ || true && \
    find . -name "libwolfssljni.so*" -exec cp -av {} /out/wolfssl/ \; && \
    find . -name "wolfssl-jsse.jar" -exec cp -av {} /out/wolfssl/ \; || true && \
    find . -name "wolfssljni.jar" -exec cp -av {} /out/wolfssl/ \; || true && \
    ls -l /out/wolfssl

# ---- 2) Build stage: Gradle로 앱 빌드 ----
FROM gradle:8.9-jdk21 AS appbuild
WORKDIR /src
COPY gradlew ./
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN ./gradlew --version
RUN ./gradlew -q dependencies || true
COPY . .
RUN ./gradlew --no-daemon clean bootJar -x test

# ---- 3) Run stage: 실행 이미지 구성 ----
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# wolfSSL 산출물 복사
COPY --from=wolfbuild /out/wolfssl/ /opt/wolfssl/

# 앱 JAR 복사
COPY --from=appbuild /src/build/libs/*.jar /app/app.jar

# 네이티브 라이브러리 경로
ENV LD_LIBRARY_PATH=/opt/wolfssl
# Boot Loader로 외부 JAR 경로 로딩
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Dloader.path=/opt/wolfssl"

EXPOSE 8080 9001/udp
ENTRYPOINT ["java","-jar","/app/app.jar","--server.port=8080"]
