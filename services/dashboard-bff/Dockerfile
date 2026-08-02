# Multi-stage build: compile with the JDK, run with a minimal JRE.
#
# Build context is services/ (see infrastructure/docker/docker-compose.yml) — all COPY
# sources below are relative to that directory, not this Dockerfile's own location.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY gradle/ gradle/
COPY gradlew build.gradle.kts settings.gradle.kts gradle/libs.versions.toml ./
COPY shared-lib/ shared-lib/
COPY dashboard-bff/ dashboard-bff/
RUN ./gradlew :dashboard-bff:bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S lynceus && adduser -S lynceus -G lynceus
WORKDIR /app
COPY --from=build /app/dashboard-bff/build/libs/*.jar app.jar
USER lynceus
EXPOSE 8083
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
    CMD wget -qO- http://localhost:8083/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
