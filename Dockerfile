# 1) Build stage (앱 빌드 그대로)
FROM gradle:8.9-jdk21 AS build
WORKDIR /src
COPY gradlew ./
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN ./gradlew --version
RUN ./gradlew -q dependencies || true
COPY . .
RUN ./gradlew --no-daemon clean bootJar -x test

# 2) Run stage
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# (A) 앱 JAR
COPY --from=build /src/build/libs/*.jar /app/app.jar

# (B) 벤더 ZIP만 복사 (로컬에서는 ZIP만 보관)
COPY vendor/ /deps/

# (C) unzip 설치 후, ZIP들을 /opt/wolfssl로 풀기
RUN apt-get update && apt-get install -y --no-install-recommends unzip file && rm -rf /var/lib/apt/lists/* && \
    mkdir -p /opt/wolfssl && \
    # wolfSSL / wolfSSL-JSSE ZIP 모두 풀기 (이름 변화에 대비해 와일드카드) \
    find /deps -maxdepth 1 -type f -name '*.zip' -print -exec unzip -q {} -d /opt/wolfssl \; && \
    # 흔한 배포 구조: 최상위/하위 디렉터리에 흩어진 jar/so를 /opt/wolfssl로 평탄화 \
    find /opt/wolfssl -type f -name '*.jar'  -exec cp -f {} /opt/wolfssl/ \; || true && \
    find /opt/wolfssl -type f \( -name 'libwolfssl*.so*' -o -name 'libwolfssljni*.so*' -o -name 'libwolfssl*.dylib' -o -name 'libwolfssljni*.dylib' \) \
        -exec cp -f {} /opt/wolfssl/ \; || true && \
    # 점검 로그(빌드 캐시 보존을 위해 실패하진 않게) \
    ls -l /opt/wolfssl || true

# (D) 런타임 경로 세팅
ENV LD_LIBRARY_PATH=/opt/wolfssl
ENV DYLD_LIBRARY_PATH=/opt/wolfssl
# loader.path에 /opt/wolfssl 추가 → 외부 JAR을 fat-jar와 함께 로드
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Dloader.path=/opt/wolfssl -Dwolfssl.dir=/opt/wolfssl"

EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar","--server.port=8080","--server.forward-headers-strategy=framework"]
