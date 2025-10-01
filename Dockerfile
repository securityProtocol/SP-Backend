# 1) Build stage
FROM gradle:8.9-jdk21 AS build
WORKDIR /src
COPY gradlew ./
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN ./gradlew --version
RUN ./gradlew -q dependencies || true
COPY . .
RUN ./gradlew --no-daemon clean bootJar -x test

# 2) Run stage (JRE + wolfJSSE 배포물 포함)
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# 앱 JAR 배치
COPY --from=build /src/build/libs/*.jar /app/app.jar

# wolfSSL JAR + JNI .so 들을 /opt/wolfssl 로 복사
# (빌드 컨텍스트 최상단에 wolfssl/ 디렉토리 준비:
#   wolfssl-jsse.jar, wolfssljni.jar, libwolfssl.so, libwolfssljni.so 등)
COPY wolfssl/ /opt/wolfssl/

# 네이티브 라이브러리 검색 경로
ENV LD_LIBRARY_PATH=/opt/wolfssl:${LD_LIBRARY_PATH}
# JVM 메모리 튜닝 유지
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 ${JAVA_TOOL_OPTIONS}"

# 포트 노출
EXPOSE 8080/tcp 5683/udp 9001/udp

# ★ 핵심: -jar 대신 -cp + JarLauncher 사용, /opt/wolfssl/* 를 클래스패스에 포함
ENTRYPOINT ["java","-cp","/app/app.jar:/opt/wolfssl/*","org.springframework.boot.loader.launch.JarLauncher","--server.port=8080","--server.forward-headers-strategy=framework"]
