# ---- 1) Build stage: wolfSSL + wolfssljni(JSSE) 빌드 ----
FROM ubuntu:24.04 AS wolfbuild
ARG DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential autoconf automake libtool pkg-config git ca-certificates \
    openjdk-21-jdk maven cmake make wget unzip file \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
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
    make -j"$(nproc)" && make install

# 1-2) wolfSSL JNI/JSSE (wolfssljni) 빌드 - CMake 사용
WORKDIR /build
RUN git clone --depth=1 https://github.com/wolfSSL/wolfssljni.git
WORKDIR /build/wolfssljni
# configure 스크립트 없는 버전 대비: CMake로 빌드
RUN cmake -S . -B build \
      -DWITH_WOLFSSL=/usr/local \
      -DBUILD_JSSE=ON \
      -DBUILD_EXAMPLES=OFF && \
    cmake --build build -j"$(nproc)"

# 산출물 모으기 (경로가 버전에 따라 다를 수 있어 find로 수집)
RUN mkdir -p /out/wolfssl && \
    cp -av /usr/local/lib/libwolfssl.so* /out/wolfssl/ || true && \
    find build -name "libwolfssljni.so*" -exec cp -av {} /out/wolfssl/ \; || true && \
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
