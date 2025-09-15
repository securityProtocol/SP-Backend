# 1) Build stage (Gradle + 캐시 최적화)
FROM gradle:8.9-jdk21 AS build
WORKDIR /src
# 의존성 레이어 캐시
COPY gradlew ./
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN ./gradlew --version
RUN ./gradlew -q dependencies || true
# 소스 복사 후 빌드
COPY . .
RUN ./gradlew --no-daemon clean bootJar -x test

# 2) Run stage (JRE만)
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/libs/*.jar /app/app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar","--server.port=8080","--server.forward-headers-strategy=framework"]
