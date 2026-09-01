# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre-jammy AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system opsmind \
    && useradd --system --gid opsmind --home-dir /app --no-create-home opsmind

WORKDIR /app
COPY --from=build /workspace/target/policy-approval-governance-service.jar app.jar

USER opsmind

# Real bug found live (project-level integration verification, 2026-09-01):
# this service's own application-local.yml defaults SERVER_PORT to 8086, not
# Spring Boot's usual 8080 -- this file was a generic copy of the sibling
# services' own Dockerfile that never accounted for that. Never caught by any
# single-service test (Testcontainers/MockMvc tests bind to a random port,
# never this Docker healthcheck).
ENV JAVA_OPTS=""
EXPOSE 8086

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD curl --fail http://localhost:8086/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
