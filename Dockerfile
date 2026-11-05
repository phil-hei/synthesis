# syntax=docker/dockerfile:1.7
# SyntheSis-Plattform: Angular-Build + Spring-Boot-Backend als ein Container.
#
# Stufen: frontend (Node 26: npm ci + ng build) → backend (Maven, Frontend ins Jar gebündelt) → runtime (JRE 21).
# Nicht enthalten: KIT-Adapter (actiTopp/logiTopp, Java 25), Ollama, .NET-ConfigTool. Ohne sie laufen
# die betroffenen Stufen als SKIPPED bzw. mit dokumentiertem Fallback (docs/08). SUMO lässt sich mit
# --build-arg WITH_SUMO=true aus dem Ubuntu-PPA installieren.

ARG NODE_VERSION=26.13.0
ARG MAVEN_IMAGE=maven:3.9.11-eclipse-temurin-21
ARG JRE_IMAGE=eclipse-temurin:21-jre-noble

FROM node:${NODE_VERSION}-bookworm-slim AS frontend
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM ${MAVEN_IMAGE} AS backend
WORKDIR /src/backend
COPY backend/pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /src/frontend/dist/synthesis-frontend/browser ./src/main/resources/static
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests \
    && cp target/synthesis-backend-*.jar /synthesis.jar

FROM ${JRE_IMAGE} AS runtime
ARG WITH_SUMO=false
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates python3 \
    && if [ "$WITH_SUMO" = "true" ]; then \
         apt-get install -y --no-install-recommends software-properties-common \
         && add-apt-repository -y ppa:sumo/stable \
         && apt-get update \
         && apt-get install -y --no-install-recommends sumo sumo-tools; \
       fi \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 --create-home --home-dir /app synthesis \
    && mkdir -p /app/data && chown -R synthesis:synthesis /app
WORKDIR /app
COPY --from=backend --chown=synthesis:synthesis /synthesis.jar /app/synthesis.jar
# Die Hilfsskripte liegen relativ zum Arbeitsverzeichnis unter ../scripts (application.yml),
# daher neben /app unter /scripts.
COPY --chown=synthesis:synthesis scripts/ /scripts/
USER synthesis
VOLUME ["/app/data"]
EXPOSE 8090
ENV SYNTHESIS_DATA_DIR=/app/data \
    SYNTHESIS_AUTH_ALLOWED_ORIGINS="" \
    SUMO_HOME=/usr/share/sumo \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.security.egd=file:/dev/./urandom"
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8090/api/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/synthesis.jar"]
