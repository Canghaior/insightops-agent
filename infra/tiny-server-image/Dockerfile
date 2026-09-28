FROM ghcr.io/canghaior/insightops-agent-server:latest

# Reuse the published runtime and replace only the locally rebuilt application.
COPY --chown=10001:10001 app.jar /app/app.jar
