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
COPY --from=build /workspace/target/user-access-authentication-service.jar app.jar

USER opsmind

ENV JAVA_OPTS=""
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD curl --fail http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
