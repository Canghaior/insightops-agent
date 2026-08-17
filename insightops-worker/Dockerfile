FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY insightops-core/pom.xml insightops-core/pom.xml
COPY insightops-infrastructure/pom.xml insightops-infrastructure/pom.xml
COPY insightops-server/pom.xml insightops-server/pom.xml
COPY insightops-worker/pom.xml insightops-worker/pom.xml
RUN chmod +x mvnw && ./mvnw --batch-mode --no-transfer-progress \
    -pl insightops-worker -am dependency:go-offline -DskipTests

COPY insightops-core/src insightops-core/src
COPY insightops-infrastructure/src insightops-infrastructure/src
COPY insightops-worker/src insightops-worker/src
RUN ./mvnw --batch-mode --no-transfer-progress \
    -pl insightops-worker -am clean package -DskipTests

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system insightops \
    && useradd --system --gid insightops --home-dir /app --shell /usr/sbin/nologin insightops
WORKDIR /app
COPY --from=build /workspace/insightops-worker/target/insightops-worker-0.1.0-SNAPSHOT.jar app.jar
RUN chown -R insightops:insightops /app
USER insightops
EXPOSE 18081
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=20s --timeout=5s --start-period=45s --retries=5 \
    CMD curl --fail --silent http://127.0.0.1:18081/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
