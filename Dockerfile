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

# 2) Run stage
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# 애플리케이션 JAR
COPY --from=build /src/build/libs/*.jar /app/app.jar

# ★ wolfSSL 런타임 패키지 복사 (빌드 컨텍스트 최상단에 wolfssl/ 디렉토리 준비)
#    - /wolfssl/wolfssl-jsse.jar
#    - /wolfssl/wolfssljni.jar
#    - /wolfssl/libwolfssl.so
#    - /wolfssl/libwolfssljni.so
COPY wolfssl/ /opt/wolfssl/

# 네이티브 라이브러리 경로
ENV LD_LIBRARY_PATH=/opt/wolfssl
# (선택) 자바 네이티브 라이브러리 경로도 강제
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.library.path=/opt/wolfssl"

EXPOSE 8080 5683/udp 9001/udp

# 중요: -jar 는 -cp 를 무시합니다.
#       추가 JAR(=wolfssl-jsse.jar, wolfssljni.jar)를 클래스패스에 포함시키려면
#       JarLauncher로 메인 실행 + -cp 로 실행해야 합니다.
ENTRYPOINT ["sh","-lc","exec java -cp '/app/app.jar:/opt/wolfssl/*' org.springframework.boot.loader.launch.JarLauncher --server.port=8080 --server.forward-headers-strategy=framework"]
