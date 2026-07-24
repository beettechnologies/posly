# Stage 1: Build the Ktor server fat JAR
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /build

COPY gradlew gradlew.bat gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY core ./core
COPY server ./server

RUN chmod +x gradlew && \
    ./gradlew :server:buildFatJar --no-daemon -q

# Stage 2: Minimal runtime image
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN groupadd --system posly && useradd --system --gid posly posly

COPY --from=builder /build/server/build/libs/*-all.jar app.jar

USER posly

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
